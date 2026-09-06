/**
 * @license
 * Copyright (c) 2014, 2024, Oracle and/or its affiliates.
 * Licensed under The Universal Permissive License (UPL), Version 1.0
 * as shown at https://oss.oracle.com/licenses/upl/
 * @ignore
 */
import * as ko from "knockout";
import { whenDocumentReady } from "ojs/ojbootstrap";
import rootViewModel from "./appController";
import "ojs/ojknockout";
import "ojs/ojmodule";
import "ojs/ojnavigationlist";
import "ojs/ojbutton";
import "ojs/ojtoolbar";

function init(): void {
  // bind your ViewModel for the content of the whole page body.
  ko.applyBindings(rootViewModel, document.getElementById("globalBody"));
}

whenDocumentReady().then(function(){
  // if running in a hybrid (e.g. Cordova) environment, we need to wait for the deviceready
  // event before executing any code that might interact with Cordova APIs or plugins.
  if (document.body.classList.contains("oj-hybrid")) {
    document.addEventListener("deviceready", init);
  } else {
    init();
  }
});
