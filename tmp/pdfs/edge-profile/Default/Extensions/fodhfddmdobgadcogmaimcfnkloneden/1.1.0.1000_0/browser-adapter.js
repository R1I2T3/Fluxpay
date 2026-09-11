import * as logger from "./logger.js";
import {getPlatformType, PlatformType, BROWSER, BrowserType, consoleLogTypes} from "./utils.js";

export const browserAPI = (() => {
    const platform = getPlatformType();

    logger.info(consoleLogTypes.LOG, "Platform Type: ", platform);
    logger.info(consoleLogTypes.LOG, "Browser : ", BROWSER);

    if (BrowserType.CHROME === BROWSER || BrowserType.EDGE === BROWSER) {
        return {
            platform: platform,
            browserName: BROWSER,
            storage: chrome.storage,
            tabs: chrome.tabs,
            runtime: chrome.runtime,
            alarms: chrome.alarms,
            webRequest: chrome.webRequest,
            declarativeNetRequest: chrome.declarativeNetRequest,
            cookies: chrome.cookies,
            idle: chrome.idle
        };
    } else if (BrowserType.FIREFOX === BROWSER) {
        return {
            platform: platform,
            browserName: BROWSER,
            storage: browser.storage,
            tabs: browser.tabs,
            runtime: browser.runtime,
            alarms: browser.alarms,
            webRequest: browser.webRequest,
            declarativeNetRequest: chrome.declarativeNetRequest,
            cookies: browser.cookies,
            idle: browser.idle
        };
    } else if (BrowserType.SAFARI === BROWSER) {
        return {
            platform: platform,
            browserName: BROWSER,
            storage: browser.storage, // Safari support for storage need to check
            tabs: browser.tabs,
            runtime: browser.runtime,
            alarms: browser.alarms,
            webRequest: browser.webRequest,
            declarativeNetRequest: browser.declarativeNetRequest, // Safari does not support DNR
            cookies: browser.cookies,
            idle: browser.idle  // Safari does not support for idle need to check alternative
        };
    }

    throw new Error("Unsupported browser");
})();
