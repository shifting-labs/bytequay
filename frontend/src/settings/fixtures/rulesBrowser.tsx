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
import { useState } from 'react';
import { flushSync } from 'react-dom';
import { createFixtureRoot } from '../../fixtures/reactFixture';
import type { AgentRuleDefinitionDto, AgentRuleDto, AgentRuleRunDto } from '../../types';
import RulesPage from '../RulesPage';
import { assert, changeInput, element, settle } from '../../fixtures/harness';
import { errorMessage } from '../../errorMessage';

const RULE = `---
name: Review PRs that need me
when: review-requested
repositories: [acme/warehouse]
does: review
agrees-when: reviewed
---
Review it for correctness first, then nits. Draft comments only.`;

const NOT_A_RULE = 'Add a "does" line to the header';

const SUGGESTIONS: AgentRuleRunDto[] = [
  { subject: 'acme/warehouse#4533', title: 'Cache block reads', occurredAt: '2026-11-05T11:00:00Z', verdict: 'PENDING', suggestion: true, authorization: null, outcome: null, skipped: 'Daily limit' },
  { subject: 'acme/warehouse#4532', title: 'Retry flaky uploads', occurredAt: '2026-11-05T10:00:00Z', verdict: 'EDITED', suggestion: true, authorization: null, outcome: null, skipped: null },
  { subject: 'acme/warehouse#4531', title: 'Split the storage writer', occurredAt: '2026-11-05T09:00:00Z', verdict: 'DISAGREED', suggestion: true, authorization: null, outcome: null, skipped: null },
];

const RUNS: AgentRuleRunDto[] = [
  { subject: 'acme/warehouse#4524', title: 'Cache block reads', occurredAt: '2026-11-04T09:00:00Z', verdict: 'PENDING', suggestion: false, authorization: null, outcome: null, skipped: null },
  { subject: 'acme/warehouse#4523', title: 'Retry flaky uploads', occurredAt: '2026-11-03T16:00:00Z', verdict: 'DISAGREED', suggestion: false, authorization: null, outcome: null, skipped: null },
  { subject: 'acme/warehouse#4522', title: 'Split the storage writer', occurredAt: '2026-11-03T12:00:00Z', verdict: 'AGREED', suggestion: false, authorization: null, outcome: null, skipped: null },
  { subject: 'acme/warehouse#4521', title: 'Fix the page cache', occurredAt: '2026-11-03T10:00:00Z', verdict: 'AGREED', suggestion: false, authorization: null, outcome: null, skipped: null },
];

/** The backend's reading, reduced to what these scenarios use: a name line, and refusal without a "does" line. */
function read(text: string): AgentRuleDefinitionDto {
  if (!text.includes('\ndoes:')) throw new Error(NOT_A_RULE);
  return {
    name: /^name: (.+)$/m.exec(text)?.[1] ?? 'Unnamed', event: 'REVIEW_REQUESTED', repositories: ['acme/warehouse'], paths: [], labels: [],
    authors: [], hosts: [], operations: [], ownedLines: false, action: 'REVIEW', agreement: 'REVIEWED', withinDays: 3, body: 'Review it.',
  };
}

function saved(id: string, text: string, paused = false): AgentRuleDto {
  return { id, version: 1, text, mode: 'OBSERVE', paused, recordSince: '2026-11-03T08:00:00Z', rule: read(text), runs: 0, agreed: 0, edited: 0, disagreed: 0 };
}

/** The shell's own save wiring around the real page, with a backend that answers at once. */
function Harness({ initial }: { initial: AgentRuleDto[] }) {
  const [rules, setRules] = useState(initial);
  return (
    <RulesPage
      rules={rules}
      busy={false}
      error={null}
      onCheck={async text => read(text)}
      onRecord={async id => (id === 'rule-1' ? RUNS : id === 'rule-3' ? SUGGESTIONS : id === 'rule-4' ? [{ ...SUGGESTIONS[0],
        authorization: { ruleId: 'rule-4', ruleName: 'Review PRs that need me', ruleVersion: 2 }, outcome: null }] : [])}
      onSave={async (text, id) => {
        read(text);
        setRules(current => (id === null ? [...current, saved(`rule-${current.length}`, text)]
          : current.map(rule => (rule.id === id ? saved(id, text, rule.paused) : rule))));
      }}
      onActivate={async rule => {
        assert(rule.version === 1, 'Activation binds the reviewed revision');
        setRules(current => current.map(each => each.id === rule.id ? { ...each, mode: 'AUTO', version: 2, paused: false } : each));
      }}
      onPause={rule => setRules(current => current.map(each => (each.id === rule.id ? { ...each, paused: !each.paused } : each)))}
      onMoveDown={rule => setRules(current => current.map(each => (each.id === rule.id ? { ...each, mode: 'OBSERVE' } : each)))}
      onRemove={rule => setRules(current => current.filter(each => each.id !== rule.id))}
    />
  );
}

function fixture(initial: AgentRuleDto[] = []) {
  const { host, root, dispose } = createFixtureRoot();
  flushSync(() => root.render(<Harness initial={initial} />));
  // While a dialog is open it owns the controls, so "Save rule" means its button.
  const scope = (): ParentNode => host.querySelector('.setw-dialog') ?? host;
  return {
    rows: () => [...host.querySelectorAll('.setw-row')].map(row => row.textContent ?? ''),
    dialog: () => host.querySelector('.setw-dialog'),
    said: () => host.querySelector('[aria-label="What the rule says"]')?.textContent ?? null,
    alert: () => scope().querySelector('[role="alert"]')?.textContent ?? null,
    record: () => [...scope().querySelectorAll('.setw-rule-run')]
      .map(run => `${run.querySelector('[role="img"]')?.getAttribute('aria-label')} ${run.textContent}`),
    async click(label: string) {
      const button = [...scope().querySelectorAll('button')]
        .find(item => item.getAttribute('aria-label') === label || item.textContent?.trim() === label);
      assert(button !== undefined && !button.disabled, `Button ${label} must be available`);
      button.click();
      await settle();
    },
    async type(value: string) {
      changeInput(element<HTMLTextAreaElement>('textarea[aria-label="Rule text"]', scope(), 'The rule box is missing'), value);
      await settle();
    },
    text: () => element<HTMLTextAreaElement>('textarea[aria-label="Rule text"]', scope(), 'The rule box is missing').value,
    dispose,
  };
}

export async function run(): Promise<{ name: string; error?: string }[]> {
  const results: { name: string; error?: string }[] = [];
  const check = async (name: string, test: (view: ReturnType<typeof fixture>) => Promise<void>, initial?: AgentRuleDto[]) => {
    const view = fixture(initial);
    try { await settle(); await test(view); results.push({ name }); }
    catch (error) { results.push({ name, error: errorMessage(error) }); }
    finally { view.dispose(); }
  };

  await check('pastes a rule, shows what it says, and saves it in Observe', async view => {
    assert(view.rows().length === 0, 'No rules are listed before one is saved');
    await view.click('Paste a rule');
    await view.type(RULE);
    await view.click('Check rule');
    const said = view.said();
    assert(said?.includes('When: A review is requested from you · in acme/warehouse') === true
      && said.includes('Does: Reviews the pull request') && said.includes('Agrees when you review it within 3 days'), `Check rule shows what the rule says: ${said}`);
    await view.type(`${RULE}\nNever post.`);
    assert(view.said() === null, 'Editing the text clears a check of the text before it');
    await view.click('Save rule');
    assert(view.dialog() === null, 'Saving closes the box');
    assert(view.rows().length === 1 && view.rows()[0].includes('Review PRs that need me') && view.rows()[0].includes('A review is requested from you · Observe'),
      `The saved rule is listed in Observe: ${view.rows().join(' | ')}`);
  });

  await check('says why a text is not a rule, on Check and on Save', async view => {
    await view.click('Paste a rule');
    await view.type(RULE.replace('does: review\n', ''));
    await view.click('Check rule');
    assert(view.alert()?.includes(NOT_A_RULE) === true && view.said() === null, `Check rule says why: ${view.alert()}`);
    await view.click('Save rule');
    assert(view.dialog() !== null && view.alert()?.includes(NOT_A_RULE) === true, 'A refused save keeps the box open with the reason');
    assert(view.rows().length === 0, 'Nothing was saved');
  });

  await check('pauses, edits, and removes a saved rule', async view => {
    await view.click('Run Review PRs that need me');
    assert(view.rows()[0].includes('A review is requested from you · Paused'), `Pausing shows on the row: ${view.rows()[0]}`);
    await view.click('Run Review PRs that need me');
    assert(view.rows()[0].includes('· Observe'), 'Running it again shows its mode');
    await view.click('Edit Review PRs that need me');
    assert(view.text() === RULE, 'Edit opens the saved text');
    await view.type(RULE.replace('need me', 'ask for me'));
    await view.click('Save rule');
    assert(view.rows().length === 1 && view.rows()[0].includes('Review PRs that ask for me'), `The edit is saved: ${view.rows().join(' | ')}`);
    await view.click('Edit Review PRs that ask for me');
    await view.click('Remove rule');
    assert(view.rows().length === 0 && view.dialog() === null, 'Remove deletes the rule and closes the box');
  }, [saved('rule-1', RULE)]);

  await check('shows a rule\'s record on its row and each run in its box', async view => {
    assert(view.rows()[0].includes('A review is requested from you · 4 runs · 67% agree · Observe'), `The row counts the record: ${view.rows()[0]}`);
    await view.click('Edit Review PRs that need me');
    const record = view.record();
    assert(record.length === 4 && record[0].startsWith('Waiting for you acme/warehouse#4524Cache block reads')
      && record[1].startsWith('You did not, in time acme/warehouse#4523') && record[3].startsWith('You agreed acme/warehouse#4521'),
    `The box lists each run with what you did: ${record.join(' | ')}`);
    await view.click('Cancel');
    await view.click('Edit Review PRs that ask for me');
    assert(view.record().length === 0, 'A rule with no runs shows no record');
  }, [{ ...saved('rule-1', RULE), runs: 4, agreed: 2, disagreed: 1 }, saved('rule-2', RULE.replace('need me', 'ask for me'))]);

  await check('moves a rule in Suggest back to Observe and shows what you did with its suggestions', async view => {
    assert(view.rows()[0].includes('A review is requested from you · 3 runs · 0% agree · Suggest'), `The row shows the rule in Suggest: ${view.rows()[0]}`);
    await view.click('Edit Review PRs that suggest');
    const record = view.record();
    assert(record.length === 3 && record[0].startsWith('Daily limit acme/warehouse#4533') && record[1].startsWith('You changed it first acme/warehouse#4532')
      && record[2].startsWith('You did not take it acme/warehouse#4531'),
    `The box shows a skipped suggestion's reason, an edited one and one turned down: ${record.join(' | ')}`);
    await view.click('Move down to Observe');
    assert(view.dialog() === null && view.rows()[0].includes('· Observe'), `Move down closes the box and observes again: ${view.rows()[0]}`);
    await view.click('Edit Review PRs that need me');
    assert(!(view.dialog()?.textContent ?? '').includes('Move down to Observe'), 'A rule in Observe has nothing to move down');
  }, [{ ...saved('rule-3', RULE.replace('need me', 'suggest')), mode: 'SUGGEST', runs: 3, edited: 1, disagreed: 1 }, saved('rule-1', RULE)]);

  await check('activates only the reviewed revision and returns edits to Observe', async view => {
    assert(view.rows()[0].includes('Observe'), 'A learned rule is not active');
    await view.click('Activate Review PRs that need me');
    assert(view.dialog()?.querySelector('[aria-label="Authorization scope"]')?.textContent?.includes('acme/warehouse'), 'Activation presents its repository scope');
    assert(view.dialog()?.textContent?.includes('without another approval card'), 'Activation explains automatic authority');
    await view.click('Cancel');
    assert(view.rows()[0].includes('Observe'), 'Cancelling grants no authority');
    await view.click('Activate Review PRs that need me');
    await view.click('Activate rule');
    assert(view.dialog() === null && view.rows()[0].includes('Active'), 'The explicit authorization activates the saved revision');
    await view.click('Edit Review PRs that need me');
    assert(view.dialog()?.textContent?.includes('Editing an active rule returns it to Observe'), 'Editing discloses that the authorization will stop');
    await view.type(`${RULE}\nCheck retry handling.`);
    await view.click('Save rule');
    assert(view.rows()[0].includes('Observe'), 'Changed instructions need fresh activation');
  }, [saved('rule-1', RULE)]);

  await check('shows an active rule’s skipped work with its reason and authorization', async view => {
    await view.click('Edit Review PRs that need me');
    assert(view.record()[0]?.startsWith('Skipped: Daily limit under Review PRs that need me'), 'A budget skip names the reason instead of suggesting agreement or success');
  }, [{ ...saved('rule-4', RULE), mode: 'AUTO', version: 2, runs: 1 }]);

  await check('distinguishes a merge rule’s prose notes from enforced conditions', async view => {
    await view.click('Activate Review PRs that need me');
    const scope = view.dialog()?.querySelector('[aria-label="Authorization scope"]')?.textContent ?? '';
    assert(scope.includes('Notes (not enforced)Only on Fridays.') && scope.includes('Conditions written in notes are not enforced.'),
      'Activation says the prose timing condition is not executable policy');
    await view.click('Cancel');
  }, [{ ...saved('rule-5', RULE), rule: { ...read(RULE), event: 'PULL_REQUEST_READY', action: 'MERGE', agreement: 'MERGED', body: 'Only on Fridays.' } }]);

  return results;
}
