/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import { testBrowserScenarios } from '../fixtures/browserScenarios';

testBrowserScenarios([
  {
    name: "answers a rule's move-up card and its finished work from the Assistant in Chromium",
    expected: 6, timeout: 30_000,
    fixture: { prefix: 'bytequay-rule-cards-', entry: 'src/assistant/fixtures/ruleCardsBrowser.tsx', global: 'RuleCardsTests', styles: true, width: 860, height: 680,
      body: `
        const results = await window.webContents.executeJavaScript('RuleCardsTests.run()');
        if (process.env.BYTEQUAY_RULE_SCREENSHOT) {
          const { writeFile } = require('node:fs/promises');
          await window.webContents.executeJavaScript('RuleCardsTests.showQuietPreview()');
          await writeFile(process.env.BYTEQUAY_RULE_SCREENSHOT, (await window.webContents.capturePage()).toPNG());
        }
      `,
    },
  },
]);
