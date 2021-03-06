/**
 * @license
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
(function() {
  'use strict';

  Polymer({
    is: 'gr-download-commands',
    properties: {
      commands: Array,
      _loggedIn: {
        type: Boolean,
        value: false,
        observer: '_loggedInChanged',
      },
      schemes: Array,
      selectedScheme: {
        type: String,
        notify: true,
      },
    },

    behaviors: [
      Gerrit.RESTClientBehavior,
    ],

    attached() {
      this._getLoggedIn().then(loggedIn => {
        this._loggedIn = loggedIn;
      });
    },

    focusOnCopy() {
      this.$$('gr-copy-clipboard').focusOnCopy();
    },

    _getLoggedIn() {
      return this.$.restAPI.getLoggedIn();
    },

    _loggedInChanged(loggedIn) {
      if (!loggedIn) { return; }
      return this.$.restAPI.getPreferences().then(prefs => {
        if (prefs.download_scheme) {
          // Note (issue 5180): normalize the download scheme with lower-case.
          this.selectedScheme = prefs.download_scheme.toLowerCase();
        }
      });
    },

    _computeSelected(item, selectedItem) {
      return item === selectedItem;
    },

    _handleSchemeTap(e) {
      e.preventDefault();
      const el = Polymer.dom(e).localTarget;
      this.selectedScheme = el.getAttribute('data-scheme');
      if (this._loggedIn) {
        this.$.restAPI.savePreferences({download_scheme: this.selectedScheme});
      }
    },
  });
})();
