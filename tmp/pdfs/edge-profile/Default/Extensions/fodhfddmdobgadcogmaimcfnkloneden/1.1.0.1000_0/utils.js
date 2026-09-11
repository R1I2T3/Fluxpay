
export const connectionRetryTime = 2000*60;
export const errorRetryTime = 3000;
export const fetchAuthDataAlarmDelay = 1;

export const GlobalAlarmConfig = Object.freeze({
    periodInMinutes: 5,
    delayInMinutes: 1,
});

export const PreferenceCheckAlarmConfig = Object.freeze({
    periodInMinutes: 60*3,//3 hours
    delayInMinutes: 5/6,//50 seconds
});

export const PreferenceKeys = Object.freeze({
    browserInitiatedLoginEnabled: "browserInitiatedLoginEnabled",
});

export const extensionDefaultPref = Object.freeze({
    [PreferenceKeys.browserInitiatedLoginEnabled]: 1,
}); 

export const getPreferenceKeyValue = (result, preferenceKey) => {
    return result.adminPref?.[preferenceKey] ?? extensionDefaultPref[preferenceKey];
}

export const defaultHtml = "<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n    <meta charset=\"UTF-8\">\n    <title>Logout html</title>\n</head>\n<body>\n    <h1>Loaded default logout html</h1>\n</body>\n</html>";

export const PlatformType = Object.freeze({
    MACOS: "macos",
    WINDOWS: "windows",
    LINUX: "linux",
    ANDROID: "android",
    IOS: "ios",
    UNKNOWN: "unknown"
});

export const BrowserType = Object.freeze({
    CHROME: "chrome",
    EDGE: "edge",
    FIREFOX: "firefox",
    SAFARI: "safari",
    UNKNOWN: "unknown"
});

export const logTypes = Object.freeze({
    INFO: "INFO",
    DEBUG: "DEBUG"
});

export const consoleLogTypes = Object.freeze({
    LOG: "log",
    WARN: "warn",
    ERROR: "error"
});
export const helperLogTypes = Object.freeze({
    INFO: "INFO",
    ERROR: "ERROR",
    DEBUG: "DEBUG",
    WARNING: "WARNING"
});

let logoutTabId = null;
export function setLogoutTabId(id) {
  logoutTabId = id;
}

export function getLogoutTabId() {
  return logoutTabId;
}

export function getPlatformType() {
    const userAgent = navigator.userAgent.toLowerCase();

    if (userAgent.includes("mac")) {
        return PlatformType.MACOS;
    } else if (userAgent.includes("win")) {
        return PlatformType.WINDOWS;
    } else if (userAgent.includes("linux")) {
        return PlatformType.LINUX;
    } else if (userAgent.includes("android")) {
        return PlatformType.ANDROID;
    } else if (/iphone|ipad|ipod/.test(userAgent)) {
        return PlatformType.IOS;
    } else {
        return PlatformType.UNKNOWN;
    }
}

export const BROWSER = (() => {

    const userAgent = navigator.userAgent.toLowerCase();

    if (userAgent.includes("edg/")) {
        return BrowserType.EDGE; // Edge includes "Edg/" in userAgent
    } else if (userAgent.includes("chrome/")) {
        return BrowserType.CHROME; // Chrome includes "Chrome/"
    } else if (userAgent.includes("firefox/")) {
        return BrowserType.FIREFOX;
    } else if (userAgent.includes("safari/")) {
        return BrowserType.SAFARI;
    } else {
        return BrowserType.UNKNOWN;
    }
})();

export function getSafariVersion() {
    const userAgent = navigator.userAgent.toLowerCase();
    let safariVersion = null;

    // Check if it's Safari and extract the version
    if (userAgent.includes("safari") && userAgent.includes("version/")) {
      const versionMatch = userAgent.match(/version\/([\d.]+)/);
      if (versionMatch && versionMatch[1]) {
        safariVersion = parseFloat(versionMatch[1]);
      }
    }
    return safariVersion;
}

export const getRequestResourceTypes = (browserType) => {
    const typesMap = {
        [BrowserType.CHROME]: ["main_frame", "sub_frame", "stylesheet", "script", "image", "font", "object", "xmlhttprequest",
            "ping", "csp_report", "media", "websocket"],
        [BrowserType.EDGE]: ["main_frame", "sub_frame", "stylesheet", "script", "image", "font", "object", "xmlhttprequest",
            "ping", "csp_report", "media", "websocket"],
        [BrowserType.FIREFOX]: ["main_frame", "sub_frame", "stylesheet", "script", "image", "font", "object", "xmlhttprequest",
            "ping", "csp_report", "media", "websocket"],
        [BrowserType.SAFARI]: ["main_frame", "sub_frame", "stylesheet", "script", "image", "font", "xmlhttprequest", "ping",
            "media", "websocket"]
    };

    return typesMap[browserType] || [];
};


export const customisedHeader = Object.freeze({
    authorization: "authorization",
    xDate: "date"
});

export function removeEmptyCookies(cookieString) {
    let separator = "";

    if (cookieString.includes("\\n")) {
        separator = "\\n";
    } else if (cookieString.includes("\n")) {
        separator = "\n";
    }

    if (separator !== "") {
        return cookieString
        .split(separator) 
        .map((line) => line.trim())
        .filter(
            (line) => line && line.includes("=") && line.split("=")[1].trim() !== ""
        )
        .join(separator);
    } else {
        let cookieArr = cookieString.split("=");
        if (cookieArr.length == 2 && cookieArr[1].trim() !== "") {
            return cookieString;
        }
        return "";
    }
}

export function formatCookies(cookieString) {
    if (!cookieString || typeof cookieString !== "string") return "";

    // Split by newline or handle the case where there is no newline
    let cookies;
    if(cookieString?.includes("\\n")) {
        cookies = cookieString.split("\\n");
    } else if(cookieString?.includes("\n")) {
        cookies = cookieString.split("\n");
    } else {
        cookies = [cookieString]
    }

    // Filter out empty lines and trim spaces
    cookies = cookies.map(cookie => cookie.trim()).filter(cookie => cookie);

    let formatedCookies = cookies.join(";");

    // Join cookies into a single string for the Cookie header
    return formatedCookies;
}

/**
 * Extracts cookies from 'Set-Cookie' headers and processes them.
 * @param {object} details - The details of the HTTP response.
 */
export function extractCookies(details) {

    const setCookieHeaders = details.responseHeaders
        .filter((header) => header.name.toLowerCase() === "set-cookie")
        .flatMap((header) => header.value.includes("\n") ? header.value.split("\n") : [header.value]);

    // Regex pattern to match "ORA_XYZ_(number)"
    const cookiePattern = /^ORA_OCIS_(\d+)$/;

    const matchedCookies = [];
    let cookieString = "";
    
    // Extract and filter cookies
    setCookieHeaders.forEach((header) => {
        const cookieParts = header.split(";"); // Split the Set-Cookie header into parts
        const [cookieName, cookieValue] = cookieParts[0].split("="); // Extract cookie name and value

        if (cookiePattern.test(cookieName.trim())) {
            const match = cookieName.trim().match(cookiePattern);
            const number = parseInt(match[1], 10); // Extract the number from the cookie name
            matchedCookies.push({name: cookieName.trim(), value: cookieValue.trim(), number});
        }
    });

    // Sort cookies by the number in ascending order
    matchedCookies.sort((a, b) => a.number - b.number);

     // Create a string with each cookie separated by "\\n" for MacOS and "\n" for Windows
    if(getPlatformType() === PlatformType.MACOS) {  
        cookieString = matchedCookies.map((cookie) => `${cookie.name}=${cookie.value}`).join("\\n");
    } else{
        cookieString = matchedCookies.map((cookie) => `${cookie.name}=${cookie.value}`).join("\n");
    }
    return cookieString;
}

/**
 * Extracts deviceToken, signature from Agent and format x-orcl-device header value.
 * @param {string, string} {deviceToken signature} - RPST token and signature.
 */
export function formatAuthorization(deviceToken, signature){
    let authorization = "";
    authorization = `Signature keyId="${deviceToken}",version="1",algorithm="rsa-sha256",headers="${customisedHeader.xDate} host",signature="${signature}"`;
    
    return authorization;   
}

export function parseJwtPayload(details) {
    const parts = details?.split('.');
    if (parts.length !== 3) return "Invalid JWT structure";
    let str = parts[1];
    str = str.replace(/-/g, '+').replace(/_/g, '/');
    const pad = str.length % 4;//atob expects padded string.
    if (pad === 2) str += '==';
    else if (pad === 3) str += '=';
    else if (pad === 1) return "Invalid base64 string";
    try {
        const decoded = atob(str);
        const payload = decodeURIComponent(
        Array.from(decoded, c =>
            '%' + ('00' + c.charCodeAt(0).toString(16)).slice(-2)
        ).join(''));
        const data = JSON.parse(payload);
        const formatTime = ts => {
            const date = new Date(ts * 1000);
            return date;
        };
        const issuedAt = data.iat ? formatTime(data.iat) : 'N/A';
        const expiresAt = data.exp ? formatTime(data.exp) : 'N/A';
        return `[Issued At - ${issuedAt}], [Expiration Time - ${expiresAt}]`;
    } catch (err) {
        return "Failed to decode JWT payload: " + err.message;
    }
}


export function checkEmptyCookie(cookies) {
        let allCookiesEmpty = false;
        let allCookies;   
        // Split cookies by "\\n" for MacOS and "\n" for Windows 
        if(getPlatformType() === PlatformType.MACOS) {   
            allCookies = cookies.split("\\n");
        } else{
            allCookies = cookies.split("\n");
        }
        // Convert to JSON object
        const CookieData = {};
        allCookies.forEach(cookie => {
            const [key, value] = cookie.split("=");
            CookieData[key.trim()] = value.trim();
        });
          
        // returns true when at least one value is an empty string ""
        allCookiesEmpty = Object.values(CookieData).every(value => value === "");

    return allCookiesEmpty;
}

// Blocks the JS thread for the given duration in milliseconds (default: 2000) 
export function waitForAuthData(ms = 2000) {
    const start = Date.now();
    while (Date.now() - start < ms) {
        // busy-wait
    }
}

  export function getHeaderValue(headers, headerName) {
    if (!headers || !Array.isArray(headers)) return null;
  
    const header = headers.find(
      (h) => h.name.toLowerCase() === headerName.toLowerCase()
    );
  
    return header ? header.value : null;
  }

  export function getCookiesInitials(cookieString, seperator) {
    
    if(!cookieString){
        return null;
    }

    if(seperator === "" || seperator === null){
        return null;
    }

    const regexExp = new RegExp("^ORA_OCIS_\\d+$");
    const cookies = cookieString.split(seperator)
        .map(cookie => cookie.trim())  
        .filter(cookie => cookie.length > 0);

    let matchingCookies = [];
    cookies.map(cookie => {
        const firstEq = cookie.indexOf('=');
        if (firstEq != -1){
            const name = cookie.substring(0, firstEq);
            const value = cookie.substring(firstEq + 1);
            if(regexExp.test(name)){
                matchingCookies.push(value?.substring(0, 10));
            }
        }
    });
    
    return `${matchingCookies.length} - [${matchingCookies.join(', ')}]`;
}

export function compareVersions(newVersion, existingVersion) {
    const newParts = newVersion.split('.').map(Number);
    const oldParts = existingVersion.split('.').map(Number);

    const maxLength = Math.max(newParts.length, oldParts.length);

    for (let i = 0; i < maxLength; i++) {
        const newVal = newParts[i] || 0;
        const oldVal = oldParts[i] || 0;

        if (newVal > oldVal) return 1;
        if (newVal < oldVal) return -1;
    }

    return 0;
}