import {hostId, signInExtURL, signInURL, env} from "./config.js";
import {customisedHeader, BROWSER, BrowserType, consoleLogTypes, helperLogTypes, getSafariVersion, getRequestResourceTypes, PreferenceKeys, extensionDefaultPref, getPreferenceKeyValue} from "./utils.js";
import { browserAPI } from "./browser-adapter.js";
import * as logger from "./logger.js";

const resourceTypes = getRequestResourceTypes(BROWSER);

const sessionRuleId1 = 1;
const sessionRuleId2 = 2;
const sessionRuleId3 = 3;
const sessionRuleId4 = 4;
const sessionRuleId5 = 5;
const safariRedirectSessionRuleId = 6;
const logoutSessionRuleId = 7;

const headerRuleId1 = 11;
const headerRuleId2 = 22;
const headerRuleId3 = 33;
const headerRuleId4 = 44;
const headerRuleId5 = 55;
const safariRedirectHeaderRuleId = 66;
const extBrowserRemoveCookieRuleId = 77; // this rule should always exist with Authorization header rule.

const queryParamRuleId1 = 111;
const queryParamRuleId2 = 222;

//3 static arrays for cookie, Auth and QueryParam ruleIds which are used in the removeRuleIds while updating/removing the DNR rules
const cookieRuleIds = [sessionRuleId1, sessionRuleId2, sessionRuleId3, sessionRuleId4, sessionRuleId5, safariRedirectSessionRuleId, logoutSessionRuleId];
const headerRuleIds = [headerRuleId1, headerRuleId2, headerRuleId3, headerRuleId4, headerRuleId5, safariRedirectHeaderRuleId, extBrowserRemoveCookieRuleId];
const queryParamRuleIds = [queryParamRuleId1, queryParamRuleId2];

function getCookieRulesDetail(token){

    const addCookieHeaderRule1 = {     // rule to add header
        id: sessionRuleId1,
        priority: 1,
        action: {
            type: 'modifyHeaders',
            requestHeaders: [
                {
                    header: 'cookie',
                    operation: 'set',
                    value: `${token}`
                }
            ]
        },
        condition: {
            regexFilter: `^https:\\/\\/${hostId}\\.identity\\.oraclecloud\\.com\\/oauth2\\/v1\\/authorize.*$`,
            resourceTypes: resourceTypes
        }
    };

    const addCookieHeaderRule2 = {     // rule to add header
        id: sessionRuleId2,
        priority: 1,
        action: {
            type: 'modifyHeaders',
            requestHeaders: [
                {
                    header: 'cookie',
                    operation: 'set',
                    value: `${token}`
                }
            ]
        },
        condition: {
            regexFilter: `^https:\\/\\/${hostId}\\.identity\\.oraclecloud\\.com\\/fed\\/v1\\/idp\\/initiatesso.*$`,
            resourceTypes: resourceTypes
        }
    };

    const addCookieHeaderRule3 = {     // rule to add header
        id: sessionRuleId3,
        priority: 1,
        action: {
            type: 'modifyHeaders',
            requestHeaders: [
                {
                    header: 'cookie',
                    operation: 'set',
                    value: `${token}`
                }
            ]
        },
        condition: {
            regexFilter: `^https:\\/\\/${hostId}\\.identity\\.oraclecloud\\.com\\/fed\\/v1\\/sp\\/initiatesso.*$`,
            resourceTypes: resourceTypes
        }
    };

    const addCookieHeaderRule4 = {     // rule to add header
        id: sessionRuleId4,
        priority: 1,
        action: {
            type: 'modifyHeaders',
            requestHeaders: [
                {
                    header: 'cookie',
                    operation: 'set',
                    value: `${token}`
                }
            ]
        },
        condition: {
            regexFilter: `^https:\\/\\/${hostId}\\.identity\\.oraclecloud\\.com\\/fed\\/v1\\/idp\\/sso.*$`,
            resourceTypes: resourceTypes
        }
    };

    const addCookieHeaderRule5 = {     // rule to add header
        id: sessionRuleId5,
        priority: 1,
        action: {
            type: 'modifyHeaders',
            requestHeaders: [
                {
                    header: 'cookie',
                    operation: 'set',
                    value: `${token}`
                }
            ]
        },
        condition: {
            regexFilter: `^https:\\/\\/${hostId}\\.identity\\.oraclecloud\\.com\\/fed\\/v1\\/sp\\/sso.*$`,
            resourceTypes: resourceTypes
        }
    };

    const logoutSessionRule = {     // rule to add header
        id: logoutSessionRuleId,
        priority: 1,
        action: {
            type: 'modifyHeaders',
            requestHeaders: [
                {
                    header: 'cookie',
                    operation: 'set',
                    value: `${token}`
                }
            ]
        },
        condition: {
            regexFilter: `^https:\\/\\/${hostId}\\.identity\\.oraclecloud\\.com\\/oauth2\\/v1\\/userlogout.*$`,
            resourceTypes: resourceTypes
        }
    };

    const addRedirectSessionRuleForSafari = {     // created due to addCookieHeaderRule4 getting added to redirect calls also in safari
        id: safariRedirectSessionRuleId,
        priority: 2,
        action: {
            type: 'modifyHeaders',
            requestHeaders: [
                {
                    header: 'cookie',
                    operation: 'remove'
                }
            ]
        },
        condition: {
            urlFilter:`https://${hostId}.identity.oraclecloud.com/sso/v1/user/login`,
            resourceTypes: resourceTypes
        }
    };

    let cookieRulesArray = [addCookieHeaderRule1, addCookieHeaderRule2, addCookieHeaderRule3, addCookieHeaderRule4, addCookieHeaderRule5, logoutSessionRule];

    if(BrowserType.SAFARI === BROWSER)
        cookieRulesArray.push(addRedirectSessionRuleForSafari);

    return cookieRulesArray;
}

function getAuthHeaderRulesDetail(authorization, xDate){

     const addHeaderRule1 = {     // rule to add header
        id: headerRuleId1,
        priority: 1,
        action: {
            type: 'modifyHeaders',
            requestHeaders: [
                {
                    header: customisedHeader.authorization,            // create constant for name
                    operation: 'set',
                    value: `${authorization}`
                },
                {
                    header: customisedHeader.xDate,
                    operation: 'set',
                    value: `${xDate}`
                }
            ]
        },
        condition: {
            regexFilter: `^https:\\/\\/${hostId}\\.identity\\.oraclecloud\\.com\\/oauth2\\/v1\\/authorize.*$`,
            resourceTypes: resourceTypes
        }
    };

    const addHeaderRule2 = {     // rule to add header
        id: headerRuleId2,
        priority: 1,
        action: {
            type: 'modifyHeaders',
            requestHeaders: [
                {
                    header: customisedHeader.authorization,
                    operation: 'set',
                    value: `${authorization}`
                },
                {
                    header: customisedHeader.xDate,
                    operation: 'set',
                    value: `${xDate}`
                }
            ]
        },
        condition: {
            regexFilter: `^https:\\/\\/${hostId}\\.identity\\.oraclecloud\\.com\\/fed\\/v1\\/idp\\/initiatesso.*$`,
            resourceTypes: resourceTypes
        }
    };

    const addHeaderRule3 = {     // rule to add header
        id: headerRuleId3,
        priority: 1,
        action: {
            type: 'modifyHeaders',
            requestHeaders: [
                {
                    header: customisedHeader.authorization,
                    operation: 'set',
                    value: `${authorization}`
                },
                {
                    header: customisedHeader.xDate,
                    operation: 'set',
                    value: `${xDate}`
                }
            ]
        },
        condition: {
            regexFilter: `^https:\\/\\/${hostId}\\.identity\\.oraclecloud\\.com\\/fed\\/v1\\/sp\\/initiatesso.*$`,
            resourceTypes: resourceTypes
        }
    };

    const addHeaderRule4 = {     // rule to add header
        id: headerRuleId4,
        priority: 1,
        action: {
            type: 'modifyHeaders',
            requestHeaders: [
                {
                    header: customisedHeader.authorization,
                    operation: 'set',
                    value: `${authorization}`
                },
                {
                    header: customisedHeader.xDate,
                    operation: 'set',
                    value: `${xDate}`
                }
            ]
        },
        condition: {
            regexFilter: `^https:\\/\\/${hostId}\\.identity\\.oraclecloud\\.com\\/fed\\/v1\\/idp\\/sso.*$`,
            resourceTypes: resourceTypes
        }
    };

    const addHeaderRule5 = {     // rule to add header
        id: headerRuleId5,
        priority: 1,
        action: {
            type: 'modifyHeaders',
            requestHeaders: [
                {
                    header: customisedHeader.authorization,
                    operation: 'set',
                    value: `${authorization}`
                },
                {
                    header: customisedHeader.xDate,
                    operation: 'set',
                    value: `${xDate}`
                }
            ]
        },
        condition: {
            regexFilter: `^https:\\/\\/${hostId}\\.identity\\.oraclecloud\\.com\\/fed\\/v1\\/sp\\/sso.*$`,
            resourceTypes: resourceTypes
        }
    };

    const addRedirectHeaderRuleForSafari = {     // created due to addHeaderRule4 getting added to redirect calls also in safari
        id: safariRedirectHeaderRuleId,
        priority: 2,
        action: {
            type: 'modifyHeaders',
            requestHeaders: [
                {
                    header: customisedHeader.authorization,
                    operation: 'remove',
                },
                {
                    header: customisedHeader.xDate,
                    operation: 'remove',
                }
            ]
        },
        condition: {
            urlFilter:`https://${hostId}.identity.oraclecloud.com/sso/v1/user/login`,
            resourceTypes: resourceTypes
        }
       };
    
    const removeCookieRuleExtBrowser = {     // rule to remove Cookie header for app initiated /authorize call for external browser login flow
        id: extBrowserRemoveCookieRuleId,
        priority: 2, //high priority than add cookie rule
        action: {
            type: 'modifyHeaders',
            requestHeaders: [
                {
                    header: 'cookie',
                    operation: 'remove',
                }
            ]
        },
        condition: {
            regexFilter: `^https:\\/\\/${hostId}\\.identity\\.oraclecloud\\.com\\/oauth2\\/v1\\/authorize.*client_id=OULAppId.*$`,
            resourceTypes: resourceTypes
        }
    };

    let authHeaderRulesArray = [addHeaderRule1, addHeaderRule2, addHeaderRule3, addHeaderRule4, addHeaderRule5, removeCookieRuleExtBrowser];

    if(BrowserType.SAFARI === BROWSER)
        authHeaderRulesArray.push(addRedirectHeaderRuleForSafari);

    return authHeaderRulesArray;
}

function getQueryParamRulesDetail() {

    const SignIn_Ext_URL_Regex = env === "prod"
    ? "^https://signon\\.oracle\\.com/signin$"
    : "^https://signon-stage\\.oracle\\.com/signin$";

    const SignIn_Int_URL_Regex = env === "prod"
    ? "^https://signon-int\\.oracle\\.com/signin$"
    : "^https://signon-int-stage\\.oracle\\.com/signin$";

    const addQueryParamRule1 = {
        id: queryParamRuleId1,
        priority: 1,
        action: {
            type: "redirect",
            redirect: {
              // transform: {
              //   queryTransform: {
              //     addOrReplaceParams: [{ key: "emp-banner", value: true }]
              //   }
              // }
              /* Above transform object can to used to retain any existing queryParam in the signon url on redirect (doesn't work in Safari currently)*/
              url: `https://${signInExtURL}?emp-banner=true`
            }
          },
          condition: {
            regexFilter: SignIn_Ext_URL_Regex, // This url filter is of idcs login page to enter emailId
            resourceTypes: ['main_frame'], // as this is an HTML page itself
          },
    };

    const addQueryParamRule2 = {
        id: queryParamRuleId2,
        priority: 1,
        action: {
            type: "redirect",
            redirect: {
              url: `https://${signInURL}?emp-banner=true`
            }
          },
          condition: {
            regexFilter: SignIn_Int_URL_Regex, // This url filter is of idcs login page to enter emailId
            resourceTypes: ['main_frame'], // this is an HTML page itself
          },
    };

    return [addQueryParamRule1, addQueryParamRule2];
}

// Internal function to update the passed ruleIds with ruleDetails
function updateDNRRules(ruleDetails, ruleIds ,logItem){
    browserAPI.declarativeNetRequest.updateDynamicRules({
        removeRuleIds: ruleIds,
        addRules: ruleDetails
    }, () => {
        if (browserAPI.runtime.lastError) {
            logger.infoHelper(helperLogTypes.ERROR,`Error updating ${logItem} DNR rules: `,browserAPI.runtime.lastError.message);
        } else {
             logger.infoHelper(helperLogTypes.INFO,`✅ ${logItem} DNR rule updated successfully.`);
        }
    });
}

// Internal function to remove the passed ruleIds
function removeDNRRules(ruleIds, logItem){
    browserAPI.declarativeNetRequest.updateDynamicRules({
        removeRuleIds: ruleIds,
    }, () => {
        if (browserAPI.runtime.lastError) {
            logger.infoHelper(helperLogTypes.ERROR,`Error removing ${logItem} DNR rules: `, browserAPI.runtime.lastError.message);
        } else {
              logger.infoHelper(helperLogTypes.INFO,`❎ ${logItem} DNR rule removed.`);
        }
    });
}

// Function to update session cookie rules
export function updateSessionRules(token) {
    const cookieRulesDetail = getCookieRulesDetail(token);
    updateDNRRules(cookieRulesDetail, cookieRuleIds, "SESSION-COOKIE");
}

// Function to remove session cookie rules
export function removeSessionRules() {
    removeDNRRules(cookieRuleIds, "SESSION-COOKIE");
}

// Function to update Authorization header rules
export function updateHeaderRules(authorization, xDate) {
    const headerRulesDetail = getAuthHeaderRulesDetail(authorization, xDate);
    updateDNRRules(headerRulesDetail, headerRuleIds, "AUTHORIZATION");
}

// Function to remove Authorization header rules
export function removeHeaderRules() {
    removeDNRRules(headerRuleIds, "AUTHORIZATION");
}

// Function to update Authorization and Cookie header rules simultaneously
export function updateSessionAndHeaderRules(authorization, xDate, token) {
    const headerRulesDetail = getAuthHeaderRulesDetail(authorization, xDate);
    const cookieRulesDetail = getCookieRulesDetail(token);
    updateDNRRules([...cookieRulesDetail, ...headerRulesDetail], [...cookieRuleIds, ...headerRuleIds], "COOKIE and AUTHORIZATION");
}

// Function to add queryParam rules in the signOn idcs calls
export function addQueryParamRules() {

     browserAPI.storage.local.get("adminPref", (result) => {
        const syncPref = getPreferenceKeyValue(result, PreferenceKeys.browserInitiatedLoginEnabled);
        if(syncPref){//1
            logger.infoHelper(helperLogTypes.WARNING, "Not adding deep-link query param rules as browserInitiatedLoginEnabled is TRUE in Admin Pref");
        } else { //0
            if(BrowserType.SAFARI === BROWSER) {
                const safariVersion = getSafariVersion();
                // If Safari version is 26.0.x , skip (https://jira.oci.oraclecorp.com/browse/EMP-12205), this redirect DNR issue will be fixed in 26.1 safari release
                if (safariVersion && safariVersion == 26) {
                    logger.infoHelper(helperLogTypes.WARNING,`Safari ${safariVersion} detected — skipping add Query Param Rules.`);
                    return;
                }
            }
            const queryParamRulesDetail = getQueryParamRulesDetail();
            updateDNRRules(queryParamRulesDetail, queryParamRuleIds, "QUERY-PARAM");
        }
    });

}

// Function to remove queryParam rules from the signOn idcs calls
export function removeQueryParamRules() {
    removeDNRRules(queryParamRuleIds, "QUERY-PARAM");
}

// Function to remove all the cookie and Auth header rules
export function removeAllRules(){
    removeDNRRules([...cookieRuleIds, ...headerRuleIds, ...queryParamRuleIds], "COOKIE, AUTHORIZATION AND QUERY-PARAM ALL");
}