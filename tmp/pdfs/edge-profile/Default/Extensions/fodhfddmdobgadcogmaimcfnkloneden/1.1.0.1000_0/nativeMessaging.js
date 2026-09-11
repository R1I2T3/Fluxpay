import {host, signInURL} from "./config.js";
import {updateSessionRules, updateHeaderRules, removeAllRules, addQueryParamRules, removeQueryParamRules, removeSessionRules, removeHeaderRules, updateSessionAndHeaderRules} from "./session-rules.js";
import {BROWSER, formatCookies, formatAuthorization, customisedHeader, defaultHtml, connectionRetryTime, errorRetryTime, BrowserType, helperLogTypes, consoleLogTypes, fetchAuthDataAlarmDelay, setLogoutTabId, PlatformType, parseJwtPayload, compareVersions, extensionDefaultPref, PreferenceKeys, getPreferenceKeyValue} from "./utils.js";
import {browserAPI} from "./browser-adapter.js";
import * as logger from "./logger.js";

let nativePort = null;
let xDate;

let setLogoutDataErrorCount = 0;
let nativeHostDisconnectCount = 0;
const FETCH_INTERVAL_MINUTES = 2;
let isOulUpgrade = false;
let isUpgradeTimePassed = false;
let disconnectListener = null;
let messageListener = null;
let extVersion = null;
let lastAuthDataCall = null;

export const OperationType = Object.freeze({
    SET_LOGOUT_DATA: "setLogoutData",
    GET_LOGOUT_DATA: "getLogoutData",
    Update_Session_Cookie: "updateSessionCookie",
    Notify_Legacy_Auth_ToApp: "notifyLegacyAuthToApp",
    GET_AUTH_DATA: "getAuthData",
    GET_SESSION_COOKIE: "getSessionCookie",
    XPC_Connection: "xpcConnectionStatus",
    SAFARI_REQUEST_STATUS: "safariRequestStatus",
    SAFARI_APP_ERROR: "safariAppError",
    SYSTEM_AWAKE_DETECTED: "systemWakeDetected",
    GET_OUL_STATUS:"getOULStatus",
    OUL_UPGRADE: "oulUpgrade",
    WRITE_EXTENSION_LOGS: "writeExtensionLogs",
    SET_EXTERNAL_LOGIN_DETAILS: "setExternalLoginDetails",
    SET_BROWSER_INITIATED_SSO_SESSION: "setBrowserInitiatedSsoSession",
    GET_LATEST_EXTENSION_VERSION: "getLatestExtensionVersion",
    GET_EXTENSION_PREFERENCES: "getExtensionPreferences",
    UNKNOWN: "unknown"
});

export const createNewAlarm = (minutes) => {
    logger.info(consoleLogTypes.LOG,"createNewAlarm called");
    browserAPI.alarms.create("fetchTokenAgain", {
        periodInMinutes: minutes
    });
}

export function validateLastResponseTime() {
    logger.info(consoleLogTypes.LOG, "validateLastResponseTime called");
    browserAPI.storage.local.get("lastFetchTime", (result) => {
        const lastFetchTime = result.lastFetchTime || 0;
        const now = Date.now();
        const diffInMinutes = (now - lastFetchTime) / (1000 * 60);

        if (diffInMinutes > FETCH_INTERVAL_MINUTES) {
            logger.infoHelper(helperLogTypes.ERROR, "More than 2 minutes passed no response received. calling getAuthData");
            checkAuthData();
            createNewAlarm(fetchAuthDataAlarmDelay);
        } else {
            logger.info(consoleLogTypes.LOG, "Received response within 2 min. calling connectNativeHost");
            connectNativeHost();
        }
    });
}

export async function connectNativeHost() {
    if(browserAPI.platform === PlatformType.WINDOWS){
        if(isOulUpgrade){
            logger.info(consoleLogTypes.WARN,"OUL Upgrade Windows is Active - skipping connecting to nativeHost");
            return;
        }
        if(isUpgradeTimePassed){
            connectNativeHostMain();
        } else {
            const isActive = await isUpgradeWindowActive();
            if (isActive) {
                logger.info(consoleLogTypes.WARN, "OUL Upgrade Windows is Active - skipping connect");
                return;
            }
            connectNativeHostMain();
        }
    } else {
        connectNativeHostMain();
    }
}

/**
 * Connects to the native messaging host.
 */
export function connectNativeHostMain() {
    if (nativePort) {
        logger.infoHelper(helperLogTypes.WARNING, "Native connection already exists. Created at : ", nativePort?._id);
        return;
    }
    extVersion = browserAPI.runtime.getManifest().version;
    nativePort = browserAPI.runtime.connectNative("com.oraclecorp.int.unifiedlogin.helper");

    if (nativePort) {
        nativePort._id = Date.now();
        logger.infoHelper(helperLogTypes.INFO, "Connected to native messaging host. Port at : ", nativePort._id, ", Extension Version : ",  extVersion);
    }

    messageListener = message => {
        logger.info(consoleLogTypes.LOG,"📩 Message received from native app ");
        logger.info(consoleLogTypes.LOG, "messageListener called for port created at : ", nativePort._id);
        logger.info(consoleLogTypes.LOG, "Message listener called, which was created at : ", messageListener._id);
        browserAPI.storage.local.set({ lastFetchTime: Date.now() });
        if (!message || Object.keys(message).length === 0) {
            logger.infoHelper(helperLogTypes.ERROR,"Empty message received - in nativePort onMessage listener");
            return;
        }
        handleNativeOperation(message);
    }

    messageListener._id = Date.now();
    nativePort.onMessage.addListener(messageListener);

    disconnectListener = () => {
        if (browserAPI.runtime.lastError) {
            logger.info(consoleLogTypes.ERROR,"Native host disconnected due to error : ",  browserAPI.runtime.lastError.message);
        }
        logger.info(consoleLogTypes.WARN," ⚠️ Native messaging host disconnected.");
        //remove existing port Listeners(onMessage and onDisconnect) and call connectNativeHost again
        nativePort.onDisconnect.removeListener(disconnectListener);
        nativePort.onMessage.removeListener(messageListener);
        nativePort = null;
        if(nativeHostDisconnectCount < 1){
            nativeHostDisconnectCount++;                
            logger.info(consoleLogTypes.WARN,"Retrying nativeHost connection - ", nativeHostDisconnectCount);
            setTimeout(() => {checkAuthData()}, errorRetryTime);
        } else {
            disableExtension(true);
            logger.info(consoleLogTypes.LOG,"All retry attempts completed. Failed to establish connection.");
        }
    };

    nativePort.onDisconnect.addListener(disconnectListener);

    //Once native app connected, first check for auth data
    if(nativeHostDisconnectCount === 0) {
        checkAuthData();
    } else {
        logger.info(consoleLogTypes.LOG,"Attempts completed while retrying nativeHost connection - ", nativeHostDisconnectCount);
    }
}

// Function to handle operations from the native host
function handleNativeOperation(message) {
    let operation
    let response
    let error

    if (message.name === "safariResponse") {
        if(message.userInfo && message.userInfo.data){
            operation = message.userInfo.data.operation;
            response = message.userInfo.data.response;
            error = message.userInfo.data.error;
        } else {
            error = "Safari extension response is not in valid format"
        }
    }else {
        operation = message.operation;
        response = message.response;
        error = message.error;
    }

    //common error/response logging for all operations.
    //For SAFARI_REQUEST_STATUS, helper logs are not needed, as adding them could create a loop.
    //This operation is mainly used to acknowledge requests received from the Safari handler.
    //To prevent random Safari browser crashes, we send an acknowledgement for every request handled in Safari.
    if (operation!==OperationType.SAFARI_REQUEST_STATUS) {
        if(error){
            logger.infoHelper(helperLogTypes.ERROR, "Operation: ", operation , " Error Received: ", error," Extension Version: ",  extVersion);
            logger.info(consoleLogTypes.ERROR,"Operation: ", operation , " Error Received: ", error, " Extension Version: ",  extVersion);
        } else if(response){
            logger.infoHelper(helperLogTypes.INFO, "Response Received for Operation: " , operation, " Extension Version: ",  extVersion);
            logger.info(consoleLogTypes.LOG,"Response Received for Operation: " , operation, " Extension Version: ",  extVersion);
        }
    }
    nativeHostDisconnectCount=0;//started receiving response from nativePort
    if (operation===OperationType.GET_LOGOUT_DATA) {
        if(!error){
            if(response===""){
                browserAPI.storage.local.set({ logoutHtmlLoaded: false }, () => {
                    logger.infoHelper(helperLogTypes.WARNING,"Empty string received in getLogoutData - logoutHtmlLoaded set to false");
                });
            } else {
                if(response !== defaultHtml){
                    loadLogoutHTML(response); 
                }else{   
                    logger.infoHelper(helperLogTypes.WARNING,"Default logout html found! Disabling extension!");
                }
                browserAPI.storage.local.set({ logoutHtmlLoaded: true }, () => {
                    logger.infoHelper(helperLogTypes.INFO,"Logout html loaded - logoutHtmlLoaded set to true");
                });
                disableExtension();//needed mainly when helper itself will inform about logout
            }
        }
    } else if (operation===OperationType.SET_LOGOUT_DATA) {
        if(error){
            if(setLogoutDataErrorCount < 1){ // retry 1 more times
                setLogoutDataErrorCount++;
                logger.infoHelper(helperLogTypes.WARNING,"Retrying setLogoutData - ", setLogoutDataErrorCount);
                setTimeout(() => {setDefaultLogoutHTML()}, errorRetryTime);
            } else {
                setLogoutDataErrorCount = 0;
            }
        } else {
            setLogoutDataErrorCount = 0;
        }
    } else if (operation===OperationType.GET_AUTH_DATA) {
        if(error){
            disableExtension(true);
        } else {
            if((!response.sessionCookie && response.deviceToken && response.signature) ||
                (response.sessionCookie === "" && response.deviceToken != "" && response.signature != "" )) {
                // deviceToken and signature present, sessionCookie missing or empty -->Notify EMP agent that user is using legacy auth flow
                browserAPI.storage.local.set({ isOulLoggedIn: false });
            }
            if (!response.deviceToken || !response.signature
                || response.deviceToken === "" || response.signature === "" ) {

                logger.infoHelper(helperLogTypes.WARNING,`deviceToken - ${response.deviceToken} signature - ${response.signature} received, disabling extension`);
                disableExtension(true);
            }
            else {
                enableExtension(!!response.sessionCookie);
                checkAndUpdateXDate(response?.signingString);
                let authLogMsg = "";
                if (!!response.sessionCookie) {
                    const cookieData = formatCookies(response.sessionCookie);
                    authLogMsg = "Valid cookie received in getAuthData - updating COOKIE and AUTH header rule and removing QUERY-PARAM rule";
                    // No need to delete ORA_OCIS_* session cookies anymore. Any cookie mismatch will be handled by the multiple IDCS Set-Cookie (ORA_OCIS_1–ORA_OCIS_5) response headers.
//                    deleteCookies(cookieData);      // delete older cookies
                    removeQueryParamRules();
                    updateSessionAndHeaderRules(formatAuthorization(response.deviceToken, response.signature),  xDate, cookieData)
                } else {
                    authLogMsg = "Empty cookie received in getAuthData - adding QUERY-PARAM rules, AUTH header rules and removing cookie rules";
                    addQueryParamRules();
                    removeSessionRules();
                    updateHeaderRules(formatAuthorization(response.deviceToken, response.signature), xDate);
                }
                logger.infoHelper(helperLogTypes.INFO, authLogMsg);
                logger.infoHelper(helperLogTypes.INFO,parseJwtPayload(response.deviceToken));
            }
        }
        createNewAlarm(fetchAuthDataAlarmDelay); // alarm should be created irrespective of success/error
    }
    else if (operation===OperationType.Notify_Legacy_Auth_ToApp) {
        if(!error){
                // response is success
                logger.infoHelper(helperLogTypes.INFO,"Legacy Auth notification is successfully sent to Agent.");
        }
    }
    else if (operation===OperationType.GET_SESSION_COOKIE) {
        if(!error){
            if (response === "") {
                logger.infoHelper(helperLogTypes.WARNING,"Empty session response for getSessionCookie. Disabling extension.");
                disableExtension();
            } else {
                //this notification comes => OUL solution is working (no untrusted/unavailable)
                browserAPI.storage.local.get(["isOulLoggedIn"], (result) => {
                    const isOulLoggedIn = result.isOulLoggedIn;
                    //Checking if isOulLoggedIn, then call getAuthData to get the latest RPST and cookies
                    if(isOulLoggedIn) {
                        const cookieData = formatCookies(response);
                        logger.infoHelper(helperLogTypes.INFO,"getSessionCookie: Received new session cookies and app is already logged in, Updating header with formatted cookie");
                        // No need to delete ORA_OCIS_* session cookies anymore. Any cookie mismatch will be handled by the multiple IDCS Set-Cookie (ORA_OCIS_1–ORA_OCIS_5) response headers.
//                        deleteCookies(cookieData);      // delete older cookies
                        updateSessionRules(cookieData);
                    } else {
                        logger.infoHelper(helperLogTypes.INFO,"getSessionCookie: Received new session cookies and app is not logged in. Calling checkAuthData.");
                        lastAuthDataCall=null;
                        checkAuthData();
                    }
                });

            }
        } else{
            checkAuthData();
        }
    } else if (operation===OperationType.Update_Session_Cookie) {
        //handle error or logs if needed.
    } else if (operation===OperationType.XPC_Connection){
        if(error){//xpc connection drop/crash
            logger.infoHelper(helperLogTypes.WARNING,"Disabling extension - received xpc connection error")
            disableExtension(true);
        }
    } else if (operation===OperationType.SAFARI_APP_ERROR) {
        // There might be cases 1. OUL App is not running. 2.  Extension handler unable to access shared container
        disableExtension(true);
    } else if (operation===OperationType.SAFARI_REQUEST_STATUS) {
        if(error && error === "RequestProcessingFailed"){
            disableExtension(true);
        }
    } else if (operation===OperationType.WRITE_EXTENSION_LOGS) {
        //handle error or logs if needed.
    } else if (operation===OperationType.SYSTEM_AWAKE_DETECTED) {
        logger.infoHelper(helperLogTypes.WARNING,"🔆 System woke from sleep! notified from helper. calling disableExtension and checkAuthData");
        disableExtension(true);
        createNewAlarm(fetchAuthDataAlarmDelay);
        lastAuthDataCall=null;
        checkAuthData();
    } else if(operation===OperationType.GET_OUL_STATUS){
        const logMsg = `rpst: ${response.rpst_status}, session: ${response.session_status}, device_trust: ${response.device_trust_status}`;
        if(response.rpst_status?.toLowerCase()==="unavailable" || response.device_trust_status?.toLowerCase()==="untrusted"){
            logger.infoHelper(helperLogTypes.ERROR,"Fully Disabling Extension as ", logMsg);
            disableExtension(true);
        } else if(response.session_status?.toLowerCase()==="inactive"){
            logger.infoHelper(helperLogTypes.ERROR,"Partially Disabling Extension as ", logMsg);
            disableExtension();
        }
        lastAuthDataCall=null;//to make checkAuthData every time when GET_OUL_STATUS notification comes
        checkAuthData();
    } else if (operation===OperationType.OUL_UPGRADE) {
        try {
            // parse and validate
            const seconds = Number(response);
            if (Number.isNaN(seconds) || seconds < 0) {
              throw new Error(`oulUpgrade response is not a valid non-negative number: ${response}`);
            }
        
            isOulUpgrade = true;
            const waitTime = seconds * 1000;
            const oulUpgradeEndTime = Date.now() + waitTime;

            logger.infoHelper(helperLogTypes.WARNING,`🚫 Disabling Extension for ${response} seconds as OUL is upgrading`);
            
            browserAPI.storage.local.set({ oulUpgradeEndTime: oulUpgradeEndTime }, () => {
                logger.info(consoleLogTypes.LOG,`oulUpgradeEndTime set to ${oulUpgradeEndTime}`);
            });
            
            nativePort.onDisconnect.removeListener(disconnectListener);
            nativePort.onMessage.removeListener(messageListener);
            nativePort.disconnect();
            nativePort = null;

            disableExtension(true);
            
            setTimeout(() => {
                isOulUpgrade = false;
                isUpgradeTimePassed = true;
                browserAPI.storage.local.set({ oulUpgradeEndTime: 0 }, () => {
                    logger.info(consoleLogTypes.LOG,`In setTimeout block, oulUpgradeEndTime set to 0`);
                });
                logger.info(consoleLogTypes.LOG,"✅ OUL Upgrade is completed ✅");
                checkAuthData();
            }, waitTime);
        } catch (err) {
            logger.info(consoleLogTypes.ERROR,  "Invalid OUL_UPGRADE payload - skipping upgrade flow: ", err.message);
        }
    } else if (operation===OperationType.GET_LATEST_EXTENSION_VERSION) {

        const newVersion = response;
        const versionLog = `Existing Version - ${extVersion}, New Version - ${newVersion}`;
        if (!newVersion || !(/^\d+(\.\d+)*$/.test(newVersion))) {
            logger.infoHelper(helperLogTypes.ERROR, "Invalid version received in getLatestExtensionVersion operation, " + versionLog);
            return ;
        }
        checkUpdateAvailable(newVersion, extVersion);

    } else if (operation===OperationType.GET_EXTENSION_PREFERENCES) {
        if(!error){
            const preferencesObj = response;
            updateBrowStoragePreferences(preferencesObj);
        }//in case of error previous value will persist in the storage
    } else if (operation===OperationType.SET_EXTERNAL_LOGIN_DETAILS) {
        //handle error or logs if needed.
    } else if (operation===OperationType.SET_BROWSER_INITIATED_SSO_SESSION) {
        //handle error or logs if needed AND log already present in Helper
    } else {
        logger.infoHelper(helperLogTypes.ERROR,"Unknown operation received in nativeMessaging response");
        createNewAlarm(fetchAuthDataAlarmDelay);
    }
}

function checkUpdateAvailable(newVersion, extVersion){
    const versionLog = `Existing Version - ${extVersion}, New Version - ${newVersion}`;
    const compare = compareVersions(newVersion, extVersion);
    if(compare == 1){
        if(BrowserType.FIREFOX === BROWSER){
            logger.infoHelper(helperLogTypes.WARNING, "Extension update available but returning as browser is Firefox, " + versionLog);
            return ;
        }
        logger.infoHelper(helperLogTypes.INFO, "🔄 Updating the extension - calling requestUpdateCheck, " + versionLog);

        const requestCheck = browserAPI.runtime.requestUpdateCheck();
        requestCheck.then((result)=>{
            logger.infoHelper(helperLogTypes.INFO, `➡️ requestUpdateCheck output | status - ${result.status}, version - ${result.version}`);
        },(error)=>{
            logger.infoHelper(helperLogTypes.ERROR, `🔴 requestUpdateCheck error received - ${error}`);
        })
    } else {
        logger.infoHelper(helperLogTypes.INFO, "No extension update available, " + versionLog);
    }
}

//method to update the extension storage items based on the final preference created
function updateExtensionValues(finalPref) {
    if(finalPref?.[PreferenceKeys.browserInitiatedLoginEnabled]){
        removeQueryParamRules();//for the case when admin browserInitiatedLoginEnabled value changed from 0 to 1, but queryParamRule will not get removed (only addition will stop) until receiving valid cookie in getAuthData 
    }
}

//method to update adminPref(for now) stored in browser storage with the prefObj received from helper
function updateBrowStoragePreferences(prefObj) {
    const adminPref = prefObj?.Admin_Extension_Pref;
    const finalExtPref = { ...extensionDefaultPref, ...adminPref }; //first add default value then override it with admin values
    browserAPI.storage.local.set( { adminPref: finalExtPref } , () => {
        if (browserAPI.runtime.lastError) {
            logger.infoHelper(helperLogTypes.ERROR,"Error updating Extension Preferences in browser storage. ", browserAPI.runtime.lastError.message);
        } else {
            updateExtensionValues(finalExtPref);
            logger.infoHelper(helperLogTypes.INFO,"Extension Preferences updated in browser storage. adminPref - ",adminPref," finalExtPref - ", finalExtPref);
        }
    })
}

export function enableExtension(isOulLoggedIn) {
    browserAPI.storage.local.get(["isExtensionEnabled", "isOulLoggedIn", "adminPref"], (result) => {
        const isExtensionEnabled = result.isExtensionEnabled;
        if(!isExtensionEnabled){
            logger.infoHelper(helperLogTypes.INFO, "Inside enableExtension() as extension was Fully Disabled, OUL solution is up now");
            const syncPref = getPreferenceKeyValue(result, PreferenceKeys.browserInitiatedLoginEnabled);
            if(syncPref){//1
                //delete any legacyAuth cookie(to avoid logouts and MyConsole redirects) or even OUL cookie(as it can be retrieved again from agent as sync is enabled)
                deleteCookies("");
            } else { //0
                //In Single browser flow, not deleting cookie will help in reducing the un-necessary passkey prompts whenever system comes from sleep or in other disable cases
                //It can lead to logout once though when legacyAuth cookie will get added with Auth header. We are going with the tradeoff between the two.
                logger.infoHelper(helperLogTypes.WARNING, "Not deleting cookies inside enableExtension as browserInitiatedLoginEnabled is FALSE in Extension Preferences");
            }
            setLogoutTabId(null);
            browserAPI.storage.local.set({ logoutHtmlLoaded: false, isExtensionEnabled: true, isOulLoggedIn: isOulLoggedIn }, () => {
                logger.infoHelper(helperLogTypes.INFO,`Fresh Auth Data Received - logoutHtmlLoaded set to false, isExtensionEnabled set to true, isOulLoggedIn set to ${isOulLoggedIn}.`);
            });
        } else if (!result.isOulLoggedIn && isOulLoggedIn) {
            // Extension was already enabled, but Oul app was not logged in.
            //no need of cookie delete in this case as any cookie present in browser will already be device bound and anyways session rules will override them
            logger.infoHelper(helperLogTypes.INFO, "Inside enableExtension() as extension was Partially Disabled - OUL app is logged in now");
            setLogoutTabId(null);
            // this block is needed only for flag update isOulLoggedIn, other flags would already been updated in above if condition
            browserAPI.storage.local.set({ logoutHtmlLoaded: false, isExtensionEnabled: true, isOulLoggedIn: isOulLoggedIn }, () => {
                logger.infoHelper(helperLogTypes.INFO,"Fresh Auth Data Received - logoutHtmlLoaded set to false, isExtensionEnabled set to true, isOulLoggedIn set to true.");
            });
        }
    });
}
/*
disableExtension() - partial disable, only related to Cookie rules
disableExtension(true) - complete disable, both Auth and Cookie rules
*/
export function disableExtension(fullDisable=false) {

    function writeLogs(logMsg) {
        if(nativePort)
            logger.infoHelper(helperLogTypes.WARNING,logMsg);
        else
            logger.info(consoleLogTypes.WARN,logMsg);
    }

    browserAPI.storage.local.get(["isExtensionEnabled", "isOulLoggedIn"], (result) => {
        const isExtensionEnabled = result.isExtensionEnabled;
        const isOulLoggedIn = result.isOulLoggedIn;
        writeLogs(" ⚠️ disableExtension called, isOulLoggedIn - " + isOulLoggedIn + ", full disable - " + fullDisable);
        if(isExtensionEnabled){
            removeSessionRules();
            addQueryParamRules();
            let flags = {}, disabledMsg = [];//to set the storage values at once
            if(isOulLoggedIn){
                //If device bound cookie is present in browser => oul app is also logged in (with cookie-sync), so delete cookies only when oulApp is logged in (to handle single browser flow correctly as well if there is any issue in syncing)
                deleteCookies("");//delete session cookie every time on disableExtension - avoid keeping device bound cookie in browser if Auth DNR rules are not present - to tackle logout and myconsole redirects.
                flags.isOulLoggedIn = false;
                disabledMsg.push("isOulLoggedIn set to false");
            }
            if(fullDisable){
                removeHeaderRules();
                flags.isExtensionEnabled = false;
                disabledMsg.push("isExtensionEnabled set to false (full disable)");
            }
            if(Object.keys(flags).length > 0 && disabledMsg.length > 0){
                browserAPI.storage.local.set(flags, () => {
                    writeLogs(disabledMsg.join(", ") + " inside disableExtension");
                });
            }
        }
    }); 
}

function checkLogoutData() {
    browserAPI.storage.local.get(["logoutHtmlLoaded"], (result) => {
        const logoutLoaded = result.logoutHtmlLoaded;
        if(logoutLoaded){//logout html has been already loaded
            return ;
        } else {//load logout html
            getLogoutHTML();
        }
    });
}

export function checkExtensionPreferences() {
    const data = 
    {
        operation: OperationType.GET_EXTENSION_PREFERENCES,
        browserClient: BROWSER
    }
    sendNativeMessage(data);
}

function loadLogoutHTML(logoutHTML) {
    logoutHTML = logoutHTML.replace(/[\n\t]/g, "");//just to be on safer side
    let targetURL = "";
    if(BrowserType.FIREFOX === BROWSER){
        logger.infoHelper(helperLogTypes.INFO,"Logout HTML loading is not supported in Firefox");
        return ;
    } else {
        targetURL = "data:text/html;charset=utf-8," + encodeURIComponent(logoutHTML);
    }
    
    browserAPI.tabs.create({ url: targetURL }, (tab) => {
    logger.infoHelper(helperLogTypes.INFO, "loaded logoutHTML in a new tab : ", tab?.id);
    //for closing the tab(when it gets redirected to the login url)
    browserAPI.tabs.onUpdated.addListener(function listener(tabId, changeInfo, updatedTab) {
        setLogoutTabId(tabId);   // identifying false notification for OUL app sign out 
        if (tabId === tab?.id && changeInfo.url && changeInfo.url.includes(signInURL)) {
            logger.infoHelper(helperLogTypes.INFO,`Closing tab - ${tabId} as its redirecting to idcs sign-in page`);
            browserAPI.tabs.remove(tabId);// Close the tab after redirect
            browserAPI.tabs.onUpdated.removeListener(listener);
            setLogoutTabId(null);  // settting LogoutTabId to null after closing tab
        }
      });
    });
}

export function checkAuthData(forceCall = false) {
    if(forceCall){
        lastAuthDataCall = null;
    }
    if(lastAuthDataCall!=null){
        const timeDiff = Date.now() - lastAuthDataCall;
        if(timeDiff<=300){//called within 300 ms.
            logger.info(consoleLogTypes.WARN, "🚫 Cancelled checkAuthData call - attempted within 300ms");
            return ;
        }
    }
    lastAuthDataCall = Date.now();
    xDate = (new Date()).toUTCString();
    const signingStringValue =  `${customisedHeader.xDate}: ${xDate}\n` + `host: ${host}`;
    const data = 
        {
            operation: OperationType.GET_AUTH_DATA,
            key: "signingString", 
            value: signingStringValue,
            browserClient: BROWSER
        }
    sendNativeMessage(data)
}

export function updateSessionCookie(newCookie) {
    const data = {
        operation: OperationType.Update_Session_Cookie,
        key: "sessionCookie",
        value: newCookie,
        browserClient: BROWSER
    }
    sendNativeMessage(data)
}

export function notifyLegacyAuthToApp() {
    const data = {
        operation: OperationType.Notify_Legacy_Auth_ToApp,
        browserClient: BROWSER
    }
    browserAPI.storage.local.get("adminPref", (result) => {
        const syncPref = getPreferenceKeyValue(result, PreferenceKeys.browserInitiatedLoginEnabled);
        if(syncPref){//1
            logger.infoHelper(helperLogTypes.WARNING, "Not sending legacy auth notification as browserInitiatedLoginEnabled is TRUE in Extension Preferences");
        } else { //0
            sendNativeMessage(data);
        }
    });
}


export function getLogoutHTML() {
    const data = {operation: OperationType.GET_LOGOUT_DATA, browserClient: BROWSER}
    sendNativeMessage(data)
}

export function setDefaultLogoutHTML() {
    const data = {
        operation: OperationType.SET_LOGOUT_DATA,
        key: "logoutData",
        value: defaultHtml,
        browserClient: BROWSER}
    logger.info(consoleLogTypes.LOG,"Sending default LogoutHTML as cookie string found is empty!");
    sendNativeMessage(data)
}

export function setExternalLoginDetails(details) {
    const data = {
        operation: OperationType.SET_EXTERNAL_LOGIN_DETAILS,
        key: "loginDetails",
        value: details,
        browserClient: BROWSER
    }
    logger.info(consoleLogTypes.LOG,"Sending app-initiated OUL login details with external browser authentication!");
    sendNativeMessage(data)
}

export function setBrowserInitiatedSsoSession(details) {
    logger.info(consoleLogTypes.LOG,"Sending browser-initiated OUL login details!");
    const data = {
        operation: OperationType.SET_BROWSER_INITIATED_SSO_SESSION,
        key: "sessionCookie",
        value: details,
        browserClient: BROWSER
    }
    browserAPI.storage.local.get("adminPref", (result) => {
        const syncPref = getPreferenceKeyValue(result, PreferenceKeys.browserInitiatedLoginEnabled);
        if(syncPref){//1
            sendNativeMessage(data);
        } else { //0
            logger.infoHelper(helperLogTypes.WARNING, "Not sending Cookie sync details as browserInitiatedLoginEnabled is FALSE in Extension Preferences");
        }
    });
}

// Function to send a message to the native host
export async function sendNativeMessage(data, isForLogToHelper = false) {
    if (!nativePort && !isForLogToHelper) {
        logger.info(consoleLogTypes.WARN,"Native host not connected. Connecting now...");
        await connectNativeHost();
    }

    if(nativePort) {
        logger.info(consoleLogTypes.LOG, "📤 Sending message to native host for operation : ", data.operation);
        nativePort.postMessage(data);
    } else {
        lastAuthDataCall = null;
        logger.info(consoleLogTypes.LOG, " 🛑 Sending message to native host failed. Port is null, for operation : ", data.operation);
    }
}

// function to delete older cookies
export function deleteCookies(cookieData, isReqCookie = false){      // cookieData is optional for disableExtension

    // Create an array from the new cookieData string if it's provided.
    const newCookiesArr = cookieData ? cookieData?.split(";") : [];

    // array to store cookies of IDCD host
    var filteredCookies = [];

    if(BrowserType.SAFARI === BROWSER){                         // Safari
        (async () => {
            try {
                const stores = await browserAPI.cookies.getAllCookieStores();     // get all cookies
                let cookies = [];
            
                for (const store of stores) {
                    const cookieOfStore = await browserAPI.cookies.getAll({ domain: host, storeId: store.id });     // get cookies from each store
                    cookies.push(...cookieOfStore);
                }

                if (!cookies || cookies.length === 0) {
                    logger.infoHelper(helperLogTypes.INFO, `No ${isReqCookie? "Request" : "Session" } cookies found for the IDCS Host `);
                    return;
                }
                
                filteredCookies = cookies;
                removeCookies(filteredCookies, newCookiesArr, isReqCookie);            // remove cookies
            } catch (error) {
                logger.infoHelper(helperLogTypes.ERROR, "Error fetching cookies: ", error);
            }
            })();
    } else {   
        browserAPI.cookies.getAll({ domain: host }, (cookies) => {     // get all cookies
            if (browserAPI.runtime.lastError) {
                logger.infoHelper(helperLogTypes.ERROR, "Error getting cookies: ", browserAPI.runtime.lastError);
                return;
            }
        
            if (!cookies || cookies.length === 0) {
                logger.infoHelper(helperLogTypes.INFO, `No ${isReqCookie? "Request" : "Session" } cookies found for the IDCS Host `);
                return;
            }

            filteredCookies = cookies;
            removeCookies(filteredCookies, newCookiesArr, isReqCookie)           // remove cookies

        })
    }
}
function removeCookies (filteredCookies, newCookiesArr, isReqCookie){        // function to remove cookies    
    // Regex pattern to match "ORA_XYZ_(number)"
    const cookiePattern =isReqCookie ? /^ORA_OCIS_REQ_(\d+)$/ : /^ORA_OCIS_(\d+)$/ ;
    // Filter out cookies that match the pattern
    const oldFilteredCookiesArr = filteredCookies?.filter((cookie) => 
        cookiePattern.test(cookie.name.trim())
    );
    // If the count of old filtered session cookies is less than the new cookie count (and at least one found), then remove cookies
    // For Request cookies (isReqCookie = true), delete all cookies without checking
    if (isReqCookie||(Number(oldFilteredCookiesArr?.length) > Number(newCookiesArr?.length) && oldFilteredCookiesArr?.length > 0)) {

        logger.infoHelper(helperLogTypes.INFO,`Deleting ${isReqCookie ? "all Request " : (oldFilteredCookiesArr?.length + " Session ")} cookies ...`);

        // Create promises for cookie removal using `map` to ensure all cokkies are delete before creating new rules
        const removalPromises = oldFilteredCookiesArr.map((cookie) => {
        const cookieDetails = {
            // Construct URL appropriately based on whether the cookie is secure
            url: `http${cookie.secure ? "s" : ""}://${cookie.domain}${cookie.path}`,
            name: cookie.name,
            storeId: cookie.storeId
        };

        logger.infoHelper(helperLogTypes.INFO,`Deleting ${isReqCookie? "Request" : "Session" } cookie started`);

        return browserAPI.cookies.remove(cookieDetails).then((removedCookie) => {
            if (removedCookie) {
                logger.info(consoleLogTypes.LOG,` Removed ${isReqCookie? "Request" : "Session" } cookie`);
            } else {
                logger.infoHelper(helperLogTypes.ERROR,`Failed to remove ${isReqCookie? "Request" : "Session" } cookie`);
            }
        });
        });

        // Wait for all cookie removal operations to complete
        Promise.all(removalPromises)
        .then(() => {
            logger.infoHelper(helperLogTypes.INFO,`Deleted ${removalPromises?.length} cookies successfully.`);
        })
        .catch((error) => {
            logger.infoHelper(helperLogTypes.ERROR,"Error during cookie removal operations: ", error);
            
        });
    }
}

function checkAndUpdateXDate(signingString) {
    if(signingString){
        const targetDateString = (signingString)?.split("date: ")[1]?.split("\n")[0];
        if(xDate !== targetDateString){     // pass targetDateString only if its different than current xDate
            if(targetDateString){               // check targetDateString is not empty/null/undefined
                const targetDate = new Date(targetDateString);
                if (!isNaN(targetDate.getTime())) {     // check if date can be parsed into milliseconds
                    const now = new Date();
                    const differenceFromNowMs = now.getTime() - targetDate.getTime();
                    const differenceFromNowSeconds = Math.floor(differenceFromNowMs / 1000);
                    logger.infoHelper(helperLogTypes.WARNING, `SigningString's date = ${targetDateString} , global variable = ${xDate} , difference = ${differenceFromNowSeconds}`);
                    xDate = targetDateString;
                } else {
                    logger.infoHelper(helperLogTypes.ERROR,`signingString date ${targetDateString} cannot be parsed into a valid number of milliseconds`);
                }
            } else {
                logger.infoHelper(helperLogTypes.ERROR,`Invalid date extracted from signingString: ${targetDateString}`);
            }
        }
    }
}
// function to check OUL upgrade windows is active
async function isUpgradeWindowActive() {
        try {
            const items = await browserAPI.storage.local.get([ "oulUpgradeEndTime" ]);

            logger.info(consoleLogTypes.LOG,"Stored value of oulUpgradeEndTime in browser storage - ", items.oulUpgradeEndTime);

            // default to 0 if unset
            const endTime = items.oulUpgradeEndTime ?? 0;

            // if endTime is passed then endTime as 0
            if(Date.now() > endTime){
                isOulUpgrade = false;
                isUpgradeTimePassed = true;
                await browserAPI.storage.local.set({ oulUpgradeEndTime: 0 });
                logger.info(consoleLogTypes.LOG,`In isUpgradeWindowActive function, oulUpgradeEndTime set to 0`);
                return false;
            }

            // return true when endTime is in the future
            return true;
        } catch (err) {
            logger.info(consoleLogTypes.ERROR,"Error reading storage: ", err);
            return false;
        }
}