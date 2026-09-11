Browser Extension for Mac and Windows 

For MAC - 
1. Clone the REPO.
2. Create a Project in Xcode, run and build the nativeMsgBinaryMacos file(present in this repo).
3. Copy a native messaging manifest file native-host-json/com.oracle.unifiedlogin.pluginhelper.chromium.json and paste in /Users/{MacId}/Library/Application Support/Google/Chrome/NativeMessagingHosts/ (create if not present).
4. Go to chrome://extensions in chrome then Load Unpacked and select the cloned extension repo folder.
5. Console output's can be seen in service-worker (present in each extension action card in this page - chrome://extensions).
6. Log in to sso protected apps (Jira/confluence etc) with Network tab open (in developer tools), headertoken variable ca be seen added to the request ("fed/v1/idp") header having the token value returned from the binary helper.


For WINDOWS -
1. Clone the REPO.
3. Create a registry key, either HKEY_LOCAL_MACHINE\SOFTWARE\Google\Chrome\NativeMessagingHosts\com.oracle.unifiedlogin.pluginhelper.chromium or HKEY_CURRENT_USER\SOFTWARE\Google\Chrome\NativeMessagingHosts\com.oracle.unifiedlogin.pluginhelper.chromium (create if not present), and set the default value of that key to the full path to the native messaging manifest file (present in this repo - native-host-json/com.oracle.unifiedlogin.pluginhelper.chromium.json).
4. Go to chrome://extensions in chrome then Load Unpacked and select the cloned extension repo folder.
5. Console output's can be seen in service-worker (present in each extension action card in this page - chrome://extensions).
6. Log in to sso protected apps (Jira/confluence etc) with Network tab open (in developer tools), headertoken variable ca be seen added to the request ("fed/v1/idp") header having the token value returned from the binary helper.