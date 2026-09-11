import { debugLog, env, infoLog } from "./config.js";
import { sendNativeMessage } from "./nativeMessaging.js";
import { BROWSER, helperLogTypes, logTypes } from "./utils.js";

//for prod - only infoHelper

export function info(logKey, ...logs){
    if (env === "prod")
        return;
    if(debugLog)
        logToConsole(logTypes.INFO, logKey, ...logs);
}

export function debug(logKey, ...logs){
    if (env === "prod")
        return;
    if (debugLog)
        logToConsole(logTypes.DEBUG, logKey, ...logs);
}

export function infoHelper(logKey, ...logs){
    if(infoLog||debugLog){
        logToHelper(logTypes.INFO, logKey, ...logs);
    }
}

export function debugHelper(logKey, ...logs){
    if(env==="prod")
        return ;
    if(debugLog){
        logToHelper(logTypes.DEBUG, logKey, ...logs);
    }
}

function logToConsole(logType, logKey, ...logs){
    const now = new Date();
    const currTime = now.toLocaleDateString() + " - " + now.toLocaleTimeString([], { hour12: false }) + "." + String(now.getMilliseconds()).padStart(3, '0');
    console[logKey](`${currTime} | [${logType}] | `, ...logs);
}

function logToHelper(logType, logKey, ...logs){
    const now = new Date();
    const currTime = now.toLocaleTimeString([], { hour12: false }) + "." + String(now.getMilliseconds()).padStart(3, '0');
    logs = [`[${currTime}]`,...logs];//add the time at which extension sent the log to helper 
    if(logKey===helperLogTypes.WARNING){
        logKey=helperLogTypes.INFO;
        logs = [`[${helperLogTypes.WARNING}]`,...logs];
    }
    const stringifiedLogs = logs.map(log => {
        if (typeof log === 'string') return log;
        try {
            return JSON.stringify(log);
        } catch (e) {
            return `[Unserializable object: ${e.message}]`;
        }
    });
    const logMessage = stringifiedLogs.join(" : ");
    const data = {
        operation:"writeExtensionLogs",
        key:logKey,
        value:logMessage,
        browserClient: BROWSER
    };
    sendNativeMessage(data, true);
}	