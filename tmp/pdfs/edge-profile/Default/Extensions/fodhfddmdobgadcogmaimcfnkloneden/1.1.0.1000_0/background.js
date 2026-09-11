import {host, hostId} from "./config.js";
import {extractCookies, checkEmptyCookie, consoleLogTypes, helperLogTypes, fetchAuthDataAlarmDelay, GlobalAlarmConfig, getLogoutTabId, waitForAuthData, BROWSER, BrowserType, removeEmptyCookies, getHeaderValue, getRequestResourceTypes, getCookiesInitials, extensionDefaultPref, PreferenceCheckAlarmConfig} from "./utils.js";
import {browserAPI} from "./browser-adapter.js";
import {connectNativeHost, checkAuthData, updateSessionCookie, setDefaultLogoutHTML, disableExtension, createNewAlarm, notifyLegacyAuthToApp, validateLastResponseTime, setExternalLoginDetails, setBrowserInitiatedSsoSession, checkExtensionPreferences} from "./nativeMessaging.js";
import { addQueryParamRules, removeAllRules } from "./session-rules.js";
import * as logger from "./logger.js";

const allowedPaths = [
    "/sso/v1/sdk/secure/session",
    "/fed/v1/user/response/login",
    "/oauth2/v1/userlogout",
    "/cloudgate/v1/oauth2/logout",
    "/sso/v1/user/logout",
    "/fed/v1/user/response/logout",
    "/fed/v1/user/request/logout",
    "/fed/v1/idp/sso",
    "/sso/v1/user/login",
    "/sso/v1/user/secure/login",
    "/sso/v1/app/launcher",
    "/fed/v1/idp/initiatesso",
    "/fed/v1/sp/initiatesso",
    "/oauth2/v1/authorize",
    "/sso/v1/sdk/session",
    "/fed/v1/idp/slo",
    "/fed/v1/sp/sso",
    "/fed/v1/sp/slo",
    "/sso/v1/sdk/idp"];

const OulLoginAllowedPaths = [
    "/fed/v1/idp/initiatesso",
    "/fed/v1/sp/initiatesso",
    "/oauth2/v1/authorize",
    "/fed/v1/sp/sso",
    "/fed/v1/idp/sso"];

const idcsLogoutPaths = [
    "/oauth2/v1/userlogout",
    "/cloudgate/v1/oauth2/logout",
    "/sso/v1/user/logout",
    "/fed/v1/user/response/logout",
    "/fed/v1/user/request/logout"];

let isBlockingEnable = false

// alarm will be triggered whenever token is about to expire
browserAPI.alarms.onAlarm.addListener(async(alarm) => {
    try {
        logger.info(consoleLogTypes.LOG,"Alarm triggered : ",alarm.name);
        if (alarm.name === "fetchTokenAgain") {
            checkAuthData();
        }

        // verifying if fetchTokenAgain alarm is created, if not then create fetchTokenAgain alarm
        else if (alarm.name === "globalAlarm") {
            const fetchTokenAgainAlarm = await browserAPI.alarms.get("fetchTokenAgain");
            if (!fetchTokenAgainAlarm) {
                logger.info(consoleLogTypes.LOG,"fetchTokenAgain alarm is not present ... Creating fetchTokenAgain alarm")
                createNewAlarm(fetchAuthDataAlarmDelay);
            }
            validateLastResponseTime();
        }
        else if (alarm.name === "preferenceCheckAlarm"){
            checkExtensionPreferences();
        }
    } catch (error) {
        logger.infoHelper(helperLogTypes.ERROR, "Error in alarm listener: ", error);
    }
});

// Listen for redirect responses
const optExtraInfoSpec = ( BROWSER === BrowserType.CHROME  || BROWSER === BrowserType.EDGE)
    ? ["responseHeaders", "extraHeaders"]
    : ["responseHeaders"];

const resourceTypes = (BROWSER === BrowserType.SAFARI) ? [] : getRequestResourceTypes(BROWSER);

browserAPI.webRequest.onHeadersReceived.addListener(
    (details) => {
        logger.info(consoleLogTypes.LOG,'onHeadersReceived called - for idcs host URL');
        const url = new URL(details.url);
        const ecid = getHeaderValue(details.responseHeaders, "x-oracle-dms-ecid");
        const dateHeader = getHeaderValue(details.responseHeaders, "date");

        let additionalLogs = " (path: " + url.pathname + ", type: " + details.type + ", tabId: " + details.tabId + ", ECID: " + ecid +
        ", dateHeader: " + dateHeader + ") ";

        if (url.hostname.includes(host) && allowedPaths.some(path => url.pathname.startsWith(path))){
            logger.infoHelper(helperLogTypes.INFO,"onHeadersReceived called - for one of the allowedPaths -" + additionalLogs);
        }

        if(details.statusCode == 400 || details.statusCode == 401){
            logger.infoHelper(helperLogTypes.ERROR, details.statusCode + " detected for -" + additionalLogs);
        }
        
        browserAPI.storage.local.get(["isExtensionEnabled", "isOulLoggedIn"], (result) => {

            if(result.isExtensionEnabled){
                // detect app initiated login flow - Start
                // isOulLoggedIn is FALSE in case of first time Authentication and is not considered because in case of ReAuthentication it will be TRUE
                const params = url?.searchParams;
                const clientId = params?.get('client_id');

                if(url.pathname === "/sso/v1/sdk/secure/session"){       

                    const location = getHeaderValue(details?.responseHeaders, "location");

                    if(location?.startsWith("https://localhost/oul")){
                        logger.infoHelper(helperLogTypes.WARNING,"App-initiated OUL login flow detected with external browser authentication." + additionalLogs);

                        const url = new URL(location);
                        let params = url?.search?.substring(1);

                        if (!params) {  // this condition is true if params is null, undefined, "", 0, false, or NaN
                            logger.infoHelper(helperLogTypes.ERROR,`params value is ${params} , hence setting to empty string.`);
                            params = "";
                        }

                        processIDCSCookies(details, additionalLogs, true, result.isOulLoggedIn, params);     // true means its appInitiatedLogin

                        return;     // this is app initiated login and here no further steps should be performed
                    }
                }
                if(clientId === "OULAppId"){ 
                    // to handle app initiated login, if any url contains OULAppId clientId then return and avoid further processing. 
                    // when app initiates login in the browser "/oauth2/v1/authorize?&client_id=OULAppId" gets hit. 
                    // According to session rules, we are going to inject only authorization header and not cookies in this call. 
                    // Due to this existing code to go in else block and detect empty cookies which leads to logout OUL app and send legacy auth notification. 

                    logger.infoHelper(helperLogTypes.WARNING,"OUL client ID detected with external browser authentication hence returning. " + additionalLogs);
                    return;
                }
                // detect app initiated login flow - End

                // if any IDCS path is detected
                if (url.hostname.includes(host) &&
                allowedPaths.some(path => url.pathname.startsWith(path))) {
                    
                    logger.info(consoleLogTypes.LOG,'Allowed IDCS path is detected.');

                    // disable extension when userlogout is called from all the browsers and for Safari track all other logout urls
                    if((url.pathname.startsWith("/oauth2/v1/userlogout")) || 
                        ((BrowserType.SAFARI === BROWSER) && (idcsLogoutPaths.some(path => url.pathname.startsWith(path))))){

                            logger.infoHelper(helperLogTypes.WARNING,`Logout path ${url.pathname} detected.` + additionalLogs);
                            // isOulLoggedIn is TRUE
                            if(result.isOulLoggedIn){
                                setDefaultLogoutHTML();
                                browserAPI.storage.local.set({ logoutHtmlLoaded: true}, () => {
                                    logger.infoHelper(helperLogTypes.WARNING,`Logout path ${url.pathname} detected and OUL app was also logged in. LogoutHtmlLoaded set to true` + additionalLogs);
                                });
                            }
                            disableExtension();
                            return;
                    }

                    // process idcs cookies
                    processIDCSCookies(details, additionalLogs, false, result.isOulLoggedIn);
                }
            } else {
                logger.infoHelper(helperLogTypes.INFO,"onHeadersReceived called - isExtensionEnabled is FALSE.");

                if ((url.hostname.includes(host) && OulLoginAllowedPaths.some(path => url.pathname.startsWith(path)))
                    && details.tabId != getLogoutTabId()) {
                    logger.infoHelper(helperLogTypes.INFO,"Notifying Agent that User is using Legacy Authentication flow." + additionalLogs);
                    notifyLegacyAuthToApp(); //Notify Agent call if isOulLoggedIn is false and User is accessing any appln.
                }
            }
        });
    },
    {
        urls: [`*://${host}/*`],
        // urls: ["<all_urls>"],
        types: resourceTypes
    },
    optExtraInfoSpec
);

const optReqExtraInfoSpec = ( BROWSER === BrowserType.CHROME  || BROWSER === BrowserType.EDGE)
    ? ["requestHeaders", "extraHeaders"]
    : ["requestHeaders"];

browserAPI.webRequest.onSendHeaders.addListener(
    function (details) {

        const url = new URL(details.url);
        if(url.hostname.includes(host) && allowedPaths.some(path => url.pathname.startsWith(path))){

            const getOriginAndPath = (url) => {
                try{
                    const urlObj = new URL(url);
                    return urlObj.origin + urlObj.pathname;
                }catch{
                    return null;
                }  
            }

            const authHeaderPresent = getHeaderValue(details.requestHeaders, "authorization") ? true : false;
            const cookie = getHeaderValue(details.requestHeaders, "cookie");
            const cookieCount = getCookiesInitials(cookie, ";"); //number of cookies matching ORA_OCIS_1,2.....
            const dateHeader = getHeaderValue(details.requestHeaders, "date");
            
            let headerLogs = "onSendHeaders called - ";
            const additionalLogs = " (path: " + url.pathname + ", type: " + details.type + ", tabId: " + details.tabId + 
            ", sessionCookieCount: " + cookieCount + ", authHeaderPresent: " + authHeaderPresent + ", dateHeader: " + dateHeader + ") ";
            headerLogs += additionalLogs;

            let headerFound = false;
            if(BROWSER === BrowserType.SAFARI){
                let referer = getHeaderValue(details?.requestHeaders, "referer");
                referer = getOriginAndPath(referer);
                if(referer){
                    headerLogs+=`Referer: `+ referer;
                    headerFound = true;
                }
            } else {
                let initiator = details.initiator; //chromium browsers
                let originUrl = details.originUrl; //firefox 

                initiator = getOriginAndPath(initiator);
                if(initiator){
                    headerLogs+=`Initiator: `+ initiator;
                    headerFound = true;
                }
                
                originUrl = getOriginAndPath(originUrl);
                if(originUrl){
                    headerLogs+=`OriginUrl: `+ originUrl;
                    headerFound = true;
                }
            }

            if(!headerFound){
                const hostIdentifier = url.searchParams.get('X-HOST-IDENTIFIER-NAME');
                if(hostIdentifier)
                    headerLogs+="host: " + hostIdentifier;
            }

            logger.infoHelper(helperLogTypes.INFO, headerLogs);
        }
    },
    { 
        urls: [`*://${host}/*`],
        types: resourceTypes
    },
    optReqExtraInfoSpec
);

browserAPI.webRequest.onBeforeRequest.addListener(
     async (details) => {
        let logMsg = "localhost/oul - onBeforeRequest listener triggered";
        try {
            const url = new URL(details.url);            
            if (url.searchParams?.size > 0) {
                logMsg += ", searchParams present";
                const authSuccessUrl = browserAPI.runtime.getURL("oulAuthenticationSuccess.html");
                let attempts = 1;
                const maxAttempts = 2;
                while (attempts <= maxAttempts) {//try 2 times
                    const updatedTab = await browserAPI.tabs.update(details.tabId, { url: authSuccessUrl });
                    await new Promise(resolve => setTimeout(resolve, 100)); // 100ms delay
                    const currentTab = await browserAPI.tabs.get(details.tabId);
                    if (currentTab?.url === authSuccessUrl) {
                        logMsg += `, URL verified after ${attempts} attempt(s), tabId - ${updatedTab?.id} updated with authSuccess page`;
                        break;
                    } 
                    if (attempts < maxAttempts) {
                        logMsg += `, URL mismatch (attempt ${attempts}), retrying...`;
                    } else {
                        logMsg += `, failed authSuccess page update after ${maxAttempts} attempts`;
                    }
                    attempts++;
                }
            }
        } catch (error) {
            logMsg+= ', Error: ' + (error?.message || 'Unknown Error');
        }
        logger.infoHelper(helperLogTypes.INFO, logMsg);
    },
    {
        urls: ["https://localhost/oul*"],
        types: resourceTypes
    },
    []
);


// fetch token by connecting to native app whenever extension is installed
browserAPI.runtime.onInstalled.addListener(() => {
    isBlockingEnable = true
    setTimeout(() => {
        logger.infoHelper(helperLogTypes.INFO, "isBlockingEnable set to false from onInstalled");
        isBlockingEnable = false
        reloadedTabs?.clear();
        redirectedUrls?.clear();
    }, 10000);

    //set/overwrite logoutHtmlLoaded to true and isExtensionEnabled to false as its a fresh install/reload
    browserAPI.storage.local.set({ logoutHtmlLoaded: true, isExtensionEnabled: false, isOulLoggedIn: false, adminPref: extensionDefaultPref }, () => {
        logger.info(consoleLogTypes.LOG,"Extension installed - logoutHtmlLoaded set to true, isExtensionEnabled set to false, isOulLoggedIn set to false");
    });
    removeAllRules(); //remove any existing rule.
    checkExtensionPreferences();
    checkAuthData(true);
    createNewAlarm(fetchAuthDataAlarmDelay);
    createGlobalAlarm();    // create globalAlarm
});

// fetch token by connecting to native app whenever browser is launched
browserAPI.runtime.onStartup.addListener(async () => {
    logger.info(consoleLogTypes.LOG,"onStartup Called.");
    isBlockingEnable = true
    setTimeout(() => {
        logger.infoHelper(helperLogTypes.INFO, "isBlockingEnable set to false from onStartup");
        isBlockingEnable = false
        reloadedTabs?.clear();
        redirectedUrls?.clear(); 
    }, 10000);
    checkExtensionPreferences();
    const alarm = await browserAPI.alarms.get("fetchTokenAgain");
    if (!alarm) {
        logger.info(consoleLogTypes.LOG,"onStartup Called. and alarm doesn't exist");
        createNewAlarm(fetchAuthDataAlarmDelay);
    }
    connectNativeHost();
    createNewAlarm(fetchAuthDataAlarmDelay);
    createGlobalAlarm();    // create globalAlarm
})

// Set a threshold (in seconds) for when the user is considered idle.
browserAPI?.idle?.setDetectionInterval(120);      // if the system hasn’t seen any user input (mouse movements, keyboard actions, touch interactions) across the computer for that duration, the idle state is triggered

browserAPI?.idle?.onStateChanged.addListener(async function idleStateChangeListener(newState) {     // it fires only when the user's state changes—specifically, it triggers after the detection interval is reached and the state shifts from "active" to "idle" or vice versa
    if (newState === "active") {
        logger.info(consoleLogTypes.LOG,"idle api called - User just became active. This might be after waking from sleep.");
   
        const alarm = await browserAPI.alarms.get("fetchTokenAgain");
        if (!alarm) {
            createNewAlarm(fetchAuthDataAlarmDelay);
        }
        validateLastResponseTime();
    }
});

// create globalAlarm to make sure alarm is always present in the browser
const createGlobalAlarm = async() => {
    const globalAlarm = await browserAPI.alarms.get("globalAlarm");
    if (!globalAlarm) {
        logger.info(consoleLogTypes.LOG, "Creating globalAlarm ...")
        browserAPI.alarms.create("globalAlarm", {
            delayInMinutes: GlobalAlarmConfig.delayInMinutes,     // first alarm triggers after 1 minutes
            periodInMinutes: GlobalAlarmConfig.periodInMinutes      // after that, it fires every 5 minutes
        });
    }
}

//Method to create preference check alarm 
const createPreferenceCheckAlarm = () => {
    logger.info(consoleLogTypes.LOG, "Creating preferenceCheckAlarm ...")
    browserAPI.alarms.create("preferenceCheckAlarm", {
            delayInMinutes: PreferenceCheckAlarmConfig.delayInMinutes, //first fire after extension is initialized ? - 1 min or 10 secs??
            periodInMinutes: PreferenceCheckAlarmConfig.periodInMinutes
        });
}


// function to process IDCS cookies for both AppInitiatedLogin and non-AppInitiatedLogin scenarios
function processIDCSCookies(details, additionalLogs, isAppInitiatedLogin, isOulLoggedIn, params=""){
    logger.infoHelper(helperLogTypes.INFO,"onHeadersReceived called - Inside processIDCSCookies method" + additionalLogs);
    let cookie = extractCookies(details);
    if(cookie !== ""){ // implies ORA_OCIS_1,2 type cookies are present
        logger.infoHelper(helperLogTypes.INFO,"Cookie received in extractCookies method from set-cookie header" + additionalLogs);

        if(BrowserType.SAFARI === BROWSER){
            logger.infoHelper(helperLogTypes.INFO,"Ignoring set-cookie header received in SAFARI");
            return ;
        }

        const emptyCookie = checkEmptyCookie(cookie);
        if(emptyCookie){ //every cookie value is ""
            
            if(isAppInitiatedLogin){       // if OUL app-initiated login and OUL may or may not be logged in
                logger.infoHelper(helperLogTypes.WARNING, "Empty Session Cookie received in App-initiated login flow." + additionalLogs);
            } else {                       // if not OUL app-initiated login
                let logMsg = "Empty Session Cookie";
                // OUL is not logged in
                if(isOulLoggedIn){
                    logMsg += "- triggering Logout.";
                    setDefaultLogoutHTML();
                    browserAPI.storage.local.set({ logoutHtmlLoaded: true}, () => {
                        logger.infoHelper(helperLogTypes.WARNING,"Empty Session Cookie and OUL app was logged in - LogoutHtmlLoaded set to true");
                    });
                }
                logger.infoHelper(helperLogTypes.WARNING, logMsg + additionalLogs);
                disableExtension();
            }
        } else {
            logger.infoHelper(helperLogTypes.INFO,"Validating non empty cookie received in onHeadersReceived method." + additionalLogs);
            const validCookieString = removeEmptyCookies(cookie); 

            if(validCookieString!==""){
                let separator = validCookieString.includes("\\n") ? "\\n" : "\n";
                const cookieCount = getCookiesInitials(validCookieString, separator); //number of cookies matching ORA_OCIS_1,2.....
                additionalLogs += ", sessionCookieCount: " + cookieCount;

                if(isAppInitiatedLogin){      // if OUL app-initiated login and OUL may or may not be logged in
                    logger.infoHelper(helperLogTypes.INFO,"Received valid new cookieString, sending to agent for app-initiated OUL login details with external browser authentication" + additionalLogs);
                    const externalLoginDetails = {
                        "cookie": validCookieString,
                        "param": params
                    };
                    const externalLoginDetailsJsonString = JSON.stringify(externalLoginDetails);    // send details to helper in string format
                    setExternalLoginDetails(externalLoginDetailsJsonString);
                } else {                     // if not OUL app-initiated login
                    if(isOulLoggedIn){              // OUL is logged in
                        logger.infoHelper(helperLogTypes.INFO,"Received valid new cookieString and OUL app was logged in. Hence sending to agent for update" + additionalLogs);
                        updateSessionCookie(validCookieString);
                    } else if(!isOulLoggedIn){      // OUL is not logged in
                        logger.infoHelper(helperLogTypes.INFO,"Received valid new cookieString and OUL app was NOT logged in. Hence sending browser-initiated OUL login details to Agent" + additionalLogs);
                        setBrowserInitiatedSsoSession(validCookieString);
                    }
                }
            } else {
                logger.infoHelper(helperLogTypes.INFO," 🛑 Received invalid cookie format from idcs" + additionalLogs);
            }                            
        }
    } else {
        logger.infoHelper(helperLogTypes.INFO,"No session cookies present in response of onHeadersReceived." + additionalLogs);
    }
}

if (BrowserType.CHROME === BROWSER || BrowserType.EDGE === BROWSER || BrowserType.FIREFOX === BROWSER) {
    // Fires when Chrome/Edge/Firefox detects a new version of the extension
    browserAPI.runtime.onUpdateAvailable.addListener((details) => {
        logger.infoHelper(helperLogTypes.WARNING, "************ Update available! onUpdateAvailable *******************", details);
        setTimeout(() => {
            browserAPI.runtime.reload();
        }, 1000);//to ensure logs are written in file before extension is reloaded
    });
}

const reloadedTabs = new Set();
const redirectedUrls = new Set();

// for Chromium based browsers only - to block and apply delay on each IDCS request by 2 seconds during the first 10 seconds after browser startup
if (BrowserType.CHROME === BROWSER || BrowserType.EDGE === BROWSER){
    browserAPI.webRequest.onBeforeRequest.addListener(
        function (details) {
            logger.infoHelper(helperLogTypes.INFO, "Inside onBeforeRequest to block and apply delay for Chrome and Edge...");

            if (isBlockingEnable) {
                logger.infoHelper(helperLogTypes.INFO, "Inside blocking section ...");
                
                const { method, url, tabId, type, requestId } = details;

                logger.infoHelper(helperLogTypes.INFO, `[Intercept] Blocking call ${method} - type: ${type} - tab: ${tabId} - request: ${requestId}`);

                if(reloadedTabs?.has(tabId) || redirectedUrls?.has(url)){
                    logger.infoHelper(helperLogTypes.INFO, "TabId is already present hence return.");
                    reloadedTabs?.delete(tabId);
                    redirectedUrls?.delete(url);
                    return;
                }

                if (tabId === -1) {
                    logger.info(consoleLogTypes.LOG, "TabId is -1, skipping.");
                    return;
                }

                if (method === "POST") {

                    reloadedTabs.add(tabId);
                    logger.infoHelper(helperLogTypes.INFO, "POST request detected. Reloading tab: ", tabId);

                    waitForAuthData();    // Even without delay this is working fine. But leadership decided to go with 2 seconds delay. 

                    browserAPI.tabs.reload(tabId);

                    return { cancel: true };
                }

                if (method === "GET") {

                    redirectedUrls.add(details.url);
                    logger.infoHelper(helperLogTypes.INFO, "GET request detected. Updating tab with same URL");
                    waitForAuthData();    // Even without delay this is working fine. But leadership decided to go with 2 seconds delay. 

                    // alternate solution to create new tab and remove older tab
                    //   browserAPI.tabs.create({ url }, function () {
                    //     setTimeout(() => {
                    //       browserAPI.tabs.remove(tabId);
                    //     }, 300);
                    //   });

                    browserAPI.tabs.update(tabId, { url: url });

                    return { cancel: true };
                }
            }
        },
        { 
            urls: [
                `https://${host}/oauth2/v1/authorize*`, 
                `https://${host}/fed/v1/idp/sso*`, 
                `https://${host}/fed/v1/sp/sso*`, 
                `https://${host}/fed/v1/idp/initiatesso*`, 
                `https://${host}/fed/v1/sp/initiatesso*`
            ],
            types: resourceTypes  
        },
        ["blocking"]
    );
}

createNewAlarm(fetchAuthDataAlarmDelay);
connectNativeHost(); 
createGlobalAlarm();
createPreferenceCheckAlarm();