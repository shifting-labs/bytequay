# ByteQuay documentation

Set up a workspace, review a change, and build with an agent.

- [Get started](#getting-started)
- [Keyboard shortcuts](#keyboard-shortcuts)
- [Use the Assistant](#assistant)
- [Review pull requests](#pull-requests)
- [Browse web pages](#web-pages)
- [Models, accounts, and agent tasks](#models)
- [Plan and organize](#issues-and-jira)
- [Connect integrations](#jira)
- [Use ByteQuay Lab](#lab)
- [Get help](#help)

<a id="what-is-bytequay"></a>

## About ByteQuay

ByteQuay is a macOS app for GitHub and GitLab reviews and local coding agents. A
**workspace** holds a repository’s work; a **task** is an agent conversation
with its own working copy. App data stays on your Mac.

ByteQuay is pre-1.0. These guides follow the development version; check your
release notes.

<a id="getting-started"></a>

## Get started

1. Download ByteQuay from
   [GitHub Releases](https://github.com/bytequay/bytequay/releases) and drag it
   to Applications. It needs macOS 14+ and includes Java.
2. Follow the setup guide to connect GitHub or GitLab and, if you like, an agent.
3. For GitHub, choose **Home → Repos you watch → + Watch** and pick a repository.
   For GitLab, follow [Set up a GitLab workspace](#set-up-a-gitlab-workspace).
4. In a GitHub workspace, open **Pull requests**, select a PR, and inspect **WorkBench → Changes**.

### Choose your setup

1. **Code hosting:** for GitHub, choose **Use my GitHub CLI login** after
   `gh auth login`, or **Connect with token →** with a classic token that has
   `repo` and `read:user`. For GitLab, select **GitLab** and connect the account
   on GitLab.com or your company server.
2. **Agent:** sign in to Codex or Claude Code, or add a DeepSeek API key, then
   pick the Development model. Review follows it unless you change it.
3. **Automation:** choose how far new tasks go, from a local commit to a merged
   PR. New installations start at **Auto-Commit**.
4. **Features:** turn on Today or the Assistant if you want them. Both use your
   Development model allowance.

You can skip the agent and still review pull requests on GitHub or GitLab. To revisit setup,
choose **Settings → General → Setup guide → Open setup**.

<a id="keyboard-shortcuts"></a>

## Keyboard shortcuts

Use the **Go** menu to move between the main pages, including from a web page
or the code editor. Finish or dismiss an open dialog before navigating.

| Shortcut | Open |
| --- | --- |
| **⌘H** or **⌥⌘1** | Home |
| **⌥⌘2** | Workspaces |
| **⌥⌘3** | Circles |
| **⌥⌘4** | Recent |
| **⌥⌘5** | Pull requests |
| **⌘,** | Settings; repository settings from a workspace |
| **⇧⌘N** | Focus or open the current workspace's task composer |
| **⌘\\** or **⌃⌘T** | Open tasks in the current workspace, or in every workspace outside one |

**⌘H** opens Home; use **ByteQuay → Hide ByteQuay** to hide the app.

**New task** preserves a composer already on screen; **Send** creates the task.
In the task switcher, use arrow keys to choose, **Enter** to open, and **Escape**
to return. Outside a workspace, the switcher shows every repository's open
tasks, running ones first, each marked with its repository's avatar. **New
task** opens **Workspaces** when no workspace is selected; choose a workspace,
then press the shortcut again. **⇧⌘J** jumps directly to the next task waiting
on you.

On app pages, **⌘K** opens Recent search and **⌘[ / ⌘]** goes back or
forward. **⌘1–6** selects PR list filters; **⌘1–4** selects Circle tabs.

Choose **Help → Keyboard shortcuts** or **Settings → Personal → Keyboard
shortcuts** for the full reference, including search, composers, and the
editor. **⌘** means Command, **⌥** Option, **⇧** Shift, and **⌃** Control.

<a id="workspaces"></a>

## Home and workspaces

Home shows your work, what others ask of you, and activity from people you
follow. A workspace holds a repository’s tasks, PRs, work items, commits, and
memory.

- **My work** sorts your issues, tickets, tasks, and PRs into **In progress**,
  **In review**, **Needs you**, and **Ready to merge**. Home shows it as one
  line of those lanes; click the line, or a lane, to open the board in the
  Assistant. A task waiting for your approval or answer counts under the
  Assistant's **Decision** and keeps its lane here with a mark that opens it.
  Incoming comments and
  review feedback on your open, non-draft PRs move them to **Needs you** until
  you reply or push an update; opening a PR does not clear that feedback.
  Approved PRs stay actionable: **Ready to merge** when merge readiness is
  confirmed, otherwise **Needs you** to check merge requirements.
  Click **Merge · no conflicts** on a ready card to merge directly. ByteQuay
  checks the latest commit and merge requirements first; repositories with a
  merge queue use that queue. Each PR has its own button when a card links
  several PRs, and any failure appears on the card.
  Open issues you opened anywhere on GitHub, updated in the last 30 days,
  wait in **In review**.
  **All my work** lists everything, including finished work.
- **Needs attention** shows up to five review requests, re-reviews, and
  mentions. **Snooze** hides one until the time you pick.
- **Approved by me** shows up to five open PRs you approved. A new review
  request brings one back for review.
- Add a workspace as a **Direct clone** or **Personal fork**. **Unwatch** keeps
  local history; **Delete local data** removes it.

### Set up a GitLab workspace

In **Workspaces → Watch repository**, choose **GitLab**. Select a saved account,
or use **Browser sign-in**. ByteQuay automatically detects an existing GitLab CLI
installation. Choose **Sign in with GitLab** when it is ready, or **Install and
sign in** to set it up first. Use your normal email or SSO login in the browser.

If Homebrew is missing, choose **Open Homebrew setup**, install it, then choose
**Check again**. The same guide appears in **Settings → Integrations → GitLab**.
The server defaults to `https://gitlab.com`; enter your company GitLab HTTPS URL
when needed, including its port or path prefix.

GitLab CLI owns browser authorization and token refresh. It requests its standard
API and repository permissions. For a company server, configure its public OAuth
application's client ID in `glab` first, as described in the
[GitLab CLI authentication guide](https://docs.gitlab.com/cli/authentication/).
The CLI keeps one active account per server; use personal access tokens to keep
additional accounts connected. Disconnecting in ByteQuay does not sign out of `glab`.

To connect with a personal access token instead, select `api`. This option also works when browser sign-in is unavailable.

Choose **Create access token** to open that server's token settings in your
browser with the ByteQuay name and required permissions prefilled. Sign in to
GitLab normally, create the token, then paste it into ByteQuay and choose
**Connect GitLab**. If your server does not preselect the permissions, select
`api` yourself before generating the token.

After sign-in, ByteQuay checks the permissions GitLab actually granted. A token
missing `api` is rejected with instructions to reconnect. **Settings →
Integrations → GitLab → Check permissions** checks an existing account again.
If the server cannot report token permissions, ByteQuay shows that verification
is unavailable; a successful sign-in alone does not confirm project access.

The first connected GitLab account is selected automatically. Type a project
name or full path, such as `group/subgroup/project`, to search, then choose
**Watch** on the project. ByteQuay creates a direct HTTPS clone using that account.
**Sync workspace** fetches updates; **Choose GitLab account** switches accounts on the
same server. Accounts can also be connected or disconnected in **Settings →
Integrations → GitLab**.

When a project or merge request cannot load, the error identifies the failed
operation and GitLab's HTTP status. Missing API permissions and expired sign-ins
include reconnect guidance. GitLab uses the same `404` response for missing and
inaccessible resources, so ByteQuay reports that uncertainty explicitly.
Choose **Copy error details** beside a login, project-search, or request-loading
error to copy its message and ByteQuay version. GitLab API failures include the
HTTP method, an endpoint template with resource identifiers removed, the UTC
time, and GitLab's request ID when available. A GitLab administrator can use that
ID to find the matching server log. Credentials, query values, and raw response
bodies are excluded from these API diagnostics.

GitLab workspaces use the same workspace home, request list, review detail,
Work items and Tasks as GitHub workspaces. Refresh reads through the selected
account. Open a request to read its discussion, checks and changes, add comments,
approve or merge. Work items lists the project's issues and can start a task from
one. Task publication creates a merge request through the workspace account.
GitLab workspaces can be linked as forks and carry backports; each backport opens
its merge request in the destination workspace's own project.

Home's **My work** and **Needs attention** list merge requests from your GitLab
accounts, and GitLab to-dos arrive as notices. Servers older than GitLab 16.11
work too; their Epics don't show child items.

GitLab supports a different set of review operations. Request-changes reviews,
multi-line review comments, merge trains and remote epic-child synchronization
are not yet integrated. CI repair follows the merge request's head pipeline. The app reports
unsupported remote actions explicitly. Project settings control GitLab merge
methods. Use **Open in GitLab** for unsupported operations.

### Plan the day with Today

Turn on Today in setup or **Settings → Agents → Features**. On working days it
lists up to five things worth doing first, each with a reason. Making the list
uses a model session; it never writes to GitHub or starts a task on its own.

- Click a row for its reason and action. **Done** or **Tomorrow** sets it aside.
- **Start task** starts suggested work with your saved automation.
- On the last working day, **This week** looks back at what merged and slipped.

**Done** on Home lists what you finished that day, with or without Today:
pull requests merged or reviewed, issues closed, and tasks and work items done.
Use the arrows or click a day on the contribution graph to see another day.
The copy button takes the day as stand-up notes; today's notes add your work in
progress from My work.

<a id="assistant"></a>

## Use the Assistant

Bring your day and waiting tasks into one conversation. Turn on **Enable
Assistant** in **Settings → Agents → Features**; each message uses your model
allowance.

1. Press **⌘J**, or type in the composer under My work on Home, and ask what
   needs you, or about the item on screen.
2. The pills under the composer open one view each: **All**, **Decision**,
   **Running**, **Results**, **Today**, and **My work**. Click the open pill
   again to go back to the conversation. Answer approvals and questions right
   on Decision's cards; a failed turn shows under Results with why. A finished
   task with a pull request shows where it stands, such as a draft PR or
   failing checks, and **Review** opens it. A result you open leaves Results.
3. Ask it to find work or open a page, such as a repository's Tasks. A page
   with a message box, such as a task, opens with the cursor in it. Pull
   request cards can **Approve**, **Merge**, **Comment**, and, on your own PRs,
   **Close**. Each click leaves a receipt.
4. Ask it for today's agenda. It reads the one on Home, or makes it when you
   ask, as **Make again** does.
5. Ask for a new Epic issue. Nothing is created until you click **Create
   issue**.
6. Start a new topic with **New conversation** or **⌘N**. After 15 minutes
   away, ⌘J opens on a new one. Go back to an earlier one from the clock
   button to keep going.
7. Keep typing while it answers: messages in the same conversation wait in
   order. Tasks and result reviews run in the background, so you can keep
   chatting with the same Assistant. **Stop this reply** stops only its current
   reply and its waiting messages; **Stop work…** on a task asks before stopping that task’s current turn.

The card’s label tells you what happens next:

- **Approval required** — allow or deny a specific action with the task’s buttons.
- **Answer needed** — answer a question before the work can continue.
- **Proposal** — review a suggestion before adopting it or creating anything.
- **Result ready** — the result is available; open it when useful. A finished turn alone does not prove the whole goal is complete.
- **Completed** — the work’s completion condition has been verified.
- **Needs attention** — inspect a blocker, failure, or uncertain outcome.
- **Progress** — work is continuing; open it for details or stop its current turn.

Reports keep their findings and evidence in the conversation after a notice is
cleared. A permission receipt says **Allowed**, **Denied**, or **Answered**;
it does not mean the work is completed. Each work card updates in place. **Activity**
keeps earlier reports, evidence, rule receipts, and why the result stayed quiet or
needed attention. **Ask about this** opens a private, read-only discussion of the
saved result; it does not restart the task. Choose **Rules** in the composer to inspect
and activate your rules, or type **@rules** to have the Assistant suggest new ones.

A result that leaves you a step to do by hand, such as running a command, needs
your attention instead of staying quiet. When that step is pushing the task's
branch or closing its PR, **Let ByteQuay push it** or **Let ByteQuay close it**
sends the task those words, and it does the step itself. The same button answers
a task that stops to ask whether ByteQuay should push its branch or close its PR.

When an active rule finishes a private PR review, the Assistant reads its saved
findings in the background and decides whether you need an interruption.
The **Result ready** card includes a short summary, the rule that started it,
and **Read full review**. Routine results stay visible without a popup, native
notification, unread dot, or Dock badge. A serious issue or notification requested
by your rule can alert you. While the summary is being prepared, a quiet Progress
card keeps the saved review accessible. Approval requests, questions, and failures
appear immediately. If the Assistant is disabled or cannot assess the result,
the card still links to the full review.

Messages waiting in a chat survive an app restart. A reply that had already started
is marked interrupted instead of repeating an action with an uncertain outcome.
Independent tasks, result reviews, and private result discussions can run while
you keep using the Assistant. For a directly requested development task, the
Assistant may make one additional local verification or repair attempt within the
original request. It respects Stop, newer input, existing permissions, and delivery
checks; unresolved questions and uncertain external actions remain visible.

**⇧⌘J** opens the task that has waited longest on your decision. The Dock counts
items that need attention; only those items can show as macOS notifications. An
approval or question waits five minutes for your answer: its card shows the
time left, and its alert comes back once in the last minute. With no answer by
then, the turn stops and Results shows **No answer within 5 minutes**.
A CI Autofix request alerts on its development task, whose page answers it.
Memory's spec checks and learning runs don't alert: one that asks stops after
five minutes, or sooner with **Stop**.

A running task that goes quiet for 10 minutes, with no command running and
nothing waiting on you, or whose command runs far longer than that program
usually takes, moves to **Decision** as **Looks stuck**, with why. Open it or
choose **Stop work…**; nothing is stopped for you.

**⌥Space** opens the same Assistant from any app, even over a full-screen
window, with voice, cards and replies. What you open from it shows in
ByteQuay. Escape puts it away and keeps what you typed. If another app, such
as Claude, already uses ⌥Space, quit it or change its shortcut, then restart
ByteQuay.

<a id="assistant-actions"></a>

### Let it act when you ask

In **Settings → Agents → Features**, you can let the Assistant start tasks,
answer tasks, change Epics and work items, or acknowledge and snooze items. Each
of these switches is off by default.

**Assistant can change pull requests** is on by default. Name the pull request,
such as "close #58" or "label #58 as bug". The Assistant can close or reopen
it, mark it ready for review or turn it back into a draft, rerun its failed
checks, ask or stop asking reviewers, add or remove labels, approve it, update
its branch with its base, and comment or change its title or description in
your own words. It approves only the commits you last saw, and a review draft
with comments stays yours to send. Updating a branch merges its base in, or
rebases it when you say rebase; GitLab can only rebase. Merging stays your
click on the pull request's card.

To rename the branch of one of your own pull requests, say so with the new
name, such as "move #58 to fix-cache-v2". A pull request can't switch
branches, so the Assistant opens a new one from the new branch with the same
title, description and draft state, then closes the old one and keeps its
branch. Reviews, comments and labels stay on the old one. A pull request a
task follows moves through that task instead.

Ask it to work on one of your own pull requests and it uses that PR's
development task, as the PR's **Development** button does, and sends your
words there. A task started some other way can't be linked to a PR later.

Ask it to backport a pull request into a repository and it starts a
**@backport** task there. The pull request doesn't have to be merged: the
backport waits for the merge, then opens its PR.

Every action leaves a receipt with **Open**. **Stop work…** asks, then cancels
the current turn it identifies. The Assistant delegates file edits and commands
to the task’s agent.

A task it starts or sends your words to reports back into the same
conversation: each unresolved approval or question, with its buttons, then its
result or why it stopped. The Assistant can review results in the background;
these reviews use your model allowance. **Reply to the task** under its latest
report sends your words straight to that task.

<a id="dictation"></a>

## Dictate instead of typing

Speak into any agent composer, the Assistant, a review, or a discussion. Your
Mac turns the speech into text, and nothing is sent until you press **Send**.
Dictation needs macOS 26 or later.

1. Turn on **Enable dictation** in **Settings → Agents → Features**. If this
   Mac still needs something, such as a speech model or microphone access, the
   Voice card says so and offers the fix.
2. In a composer, hold **right ⌥** while you speak, then let go. Or click the
   microphone to start and again to stop. **Esc** cancels.
3. Your words show above the draft as you talk. The finished text lands at the
   cursor, and **⌘Z** removes it.

To speak one language and write in another, choose **I speak** and **Write it
in** on the Voice card. The first time, Apple asks in a small window to
download that translation. Hover the finished words to see what you said.

### Your voice stays on your Mac

- Apple's speech engine on your Mac turns your voice into text. No audio goes
  to a cloud service.
- Agents and model providers see only the text you send, as if you had typed
  it. They never receive audio.
- A recording exists only while you talk and is deleted once its text is in
  your draft. If it can't be turned into text, choose **Retry** or
  **Discard**; either way it is deleted when ByteQuay quits.
- Translation runs on your Mac too. The only downloads are Apple's speech and
  translation models, which come to your Mac.


<a id="pull-requests"></a>

## Review a pull request

1. Open **Pull requests** and filter, or search by title, `#number`, or
   `@author`.
2. Read checks and activity in **PR**. Inspect files in **WorkBench → Changes**.
3. Select diff lines, choose **Add to review**, then **Submit review**.
4. Merge from the header. For branches with a merge queue, **Merge when ready**
   schedules the merge while CI runs; use **Cancel scheduled merge** to cancel
   before it enters the queue. **Rebase and merge** waits for passing checks.
   **Monitor PR** follows updates.

**Post now** and **Reply** publish at once; **Add to review** keeps comments
pending until you submit.

To update **Reviewers** or **Assignees**, search and select each person (or
reviewer team), then choose **Save changes** to apply the selection together.
**Cancel** or closing the picker discards unsaved selections.

<a id="explore-locally"></a>

## Explore code and tests

For a watched PR, open **WorkBench → Tears of time → Create worktree to
explore**. It opens a local editor and starts no agent.

- **Shift twice** or **⌘⇧F** searches everywhere; **⌘F** searches the file.
- Select a file in the left tree to preview it; double-click to keep its tab open.
- Scroll the file tree horizontally to read long names. **Locate current file**
  switches to **All files**, expands its folders, and brings it into view.
- **⌘B** jumps to a definition, **⌥F7** finds usages, **⌃⌥H** shows callers,
  and **⌥⌘←** / **⌥⌘→** go back and forward between code locations with the
  IDEA keymap.
- Edit and save files, then use a test’s play button to run it. **Run** shows
  results and output; **Terminal** opens a shell in the task’s working copy.
- **Blame** shows line authorship; **Git history** shows commits and changes.
- Click a colored gutter marker to preview added, updated, or deleted code
  compared with the branch’s base. **Escape** closes the preview. It shows saved
  changes; save your edits to include them. **Show Saved Change at Cursor** is
  also available in the editor’s context menu and command palette.

The editor keeps its IDEA-style layout and VS Code navigation and test support.
Debugger controls, automatic completion, and inlay hints are disabled. Rust
checks run with your tests or terminal commands rather than on every save.

Java, Go, and Rust language extensions are included. Viewing and editing Go or
Rust files uses the same editor, file tree, Git history, and terminal. Project
navigation and test runs also need the language's local tools: Go and `gopls`
for Go projects, or Rust's `cargo`, `rustc`, and `rust-src` for Rust projects.
Open the project root containing `go.mod`/`go.work` or `Cargo.toml` so the
language service can resolve its packages and dependencies.

The selected editor stays running while ByteQuay is minimized, so returning
does not restart its language service. This keeps more memory in use for a
faster return. Other unused editors can release their runtime after about two
minutes out of view. Returning to one of those editors restarts it and restores
its tabs; language navigation may take a moment to become ready.
Unsaved edits, running tests, pending operations, and open terminal sessions
keep the runtime alive. A silent terminal is still protected; use `exit` when
you are finished with its shell.

On any PR, **Development** opens or creates its agent task. On someone else’s
PR, ByteQuay asks first; the agent works on its own local branch and pushes
nothing unless you ask. **Review** opens or starts a review task. Both use a robot icon; an assigned Development task has
a green background, and an assigned Review task has a blue background.

<a id="private-review"></a>

## Ask for a private review

Watch the repository and pick a ready Review model first.

1. Choose **WorkBench → Summary → Start review**, or **Review changes** in a
   task. If the task's agent is working, the review starts when it finishes.
   To review only some commits, pick them in **Commits** first. **Review
   again** later covers the whole pull request.
2. Read **What changed** and the findings. Each finding appears as soon as the
   agent confirms it and stays Provisional until the review finishes.
   **Walkthrough** follows one example input through the code, step by step,
   to the result that goes wrong, then shows the fix; it is never posted.
   **Ask reviewer** opens a private discussion.
3. **Add to my review** turns a finding into a comment you publish with **Submit
   review**. **Request fix → Send to developer** asks the development agent to
   fix it instead.
4. **Review latest** checks new commits.

**View in Changes** opens a finding on the displayed diff. Findings from an
earlier review stay inline when the code at their saved lines is unchanged.
Otherwise, their cards appear below the file with the original code and discussion.
Findings on files outside the diff appear after the changed files.

If a review could not finish, use **Resume review**.

<a id="ask-from-review"></a>

## Ask from a review card

1. On someone else’s review comment, choose **Ask agent** and ask about it.
2. **Draft with agent** writes a reply that stays private until you choose
   **Comment**.

**Resolve locally** closes only the private discussion. On a development task’s
PR, **Fix with agent** sends the comment to that task’s agent.

<a id="link-previews"></a>

## Preview PR and issue links

Hover a PR or issue link to see its title, status, and description. Drag the
card to keep it open; **Escape** closes it.

<a id="web-pages"></a>

## Browse web pages

Choose **New page** in the Web pages column and enter an address, or type
words to search Google. **Pin** keeps a page after restart, and **⌘F** finds
text on it. **Back to app** keeps the page open.

<a id="models"></a>

## Choose your model

Open **Settings → Agents → Models**.

- **Codex or Claude Code:** choose **Sign in**. If the CLI is missing or too
  old, run the command shown, then choose **Check**.
- **API key:** choose **Add API key** and a provider, such as DeepSeek.
  DeepSeek tasks need the Codex CLI, but no Codex account.
- Once it shows **Ready**, pick your Development and Review models. Repository
  **Model overrides** can choose differently.

Keys are stored encrypted on your Mac.

<a id="claude-accounts"></a>

## Keep Claude Code accounts separate

1. In **Models → Claude accounts**, choose **Add Claude account** and sign in.
2. Pick the account for Development or Review.
3. To keep an account to one repository, add it in that repository’s **Model
   overrides**, or choose **Use only here**.

For a company gateway such as LiteLLM, choose **Gateway token** and enter its
URL and token instead of an email. The token is stored encrypted on your Mac.

Existing tasks keep the account they started with.

## Keep Codex work and personal logins separate

1. In a repository's **Model overrides → Codex accounts**, choose **Add Codex account**.
2. Select **Codex login**, enter a name and your work email, then choose **Add and sign in**.
3. Choose your work account and workspace in the browser. ByteQuay verifies the email.
4. Select that account for **Development** and **Review**.

Each login has a separate profile. Accounts added in repository settings cannot
run in other repositories; accounts added in global **Models** settings are shared.
Existing tasks keep their original account. Removing an account from ByteQuay
preserves its Codex-owned login and session files.

## Use Codex through a company gateway

1. In **Models → Codex accounts**, choose **Add Codex account**, then **Gateway token**.
2. Enter a name, the Responses API base URL (including `/v1` when required),
   and your gateway token.
3. Choose the account and a standard Codex model your gateway supports for
   Development or Review, such as `gpt-6-astra`.

Tokens are stored encrypted on your Mac; no ChatGPT sign-in is needed. Add an
account in a repository's **Model overrides**, or choose **Use only here**, to
limit it to that repository. Existing tasks keep their account. The model list
comes from Codex; gateway access is checked when a task runs. Native web search
is unavailable through gateway accounts.

<a id="agent-tasks"></a>

## Start an agent task

1. Open **Tasks → + New task** and pick a ready model.
2. Describe the outcome and how to check it.
3. Check **Automation**, then **Send**. The task gets its own branch and
   working copy.

Choose **Backport**, **Fix issues and failures**, or **Review code and suggest
changes** above the composer, or type **@backport**, **@fix**, or **@review**
followed by a space. The picker lists 20 items at a time; scroll down for more.
Backport lists recently merged PRs of this repository's upstream in its repo
line, or of this repository at the top of the line; Fix lists this repository's
newest open issues; and Review lists its newest open PRs by other authors. Type
a word of a title, or a number, to search within that category. Matching
ignores capitalization, and a number also finds items that mention it. Only the
first word after the command is searched; adding a space after it closes the
picker so you can continue
writing instructions. Click a result, or use the arrow keys and Enter or Tab,
to insert its title and number as blue text in the composer. Titles longer than
40 characters are shortened. Hover over the blue text to see the full title;
click it to open the issue or PR. Add any instructions, then send when ready.
Escape dismisses the picker. Review asks for findings and
suggestions without changing code or publishing a review.

To contribute to a repository, start the message with **@contribute**, choose
**Contribute** on Home, or ask the Assistant to find a contribution there. The
task reads the code itself as well as open issues, picks one small, worthwhile
change, makes it with the evidence that kind of change needs, and writes a short
PR description in the repository's own template and style. To ask for one kind,
put a focus right after the command: **bug** (with a regression test),
**performance** (with before and after measurements), **cleanup** (same
behavior, simpler code), **tests**, or **docs**, as in `@contribute performance`.
The composer lists them after the command; type a letter or use the arrow keys
to pick one, or press Enter to send without one. Add more words to narrow the
search. If nothing qualifies, the task says so instead of making a change. Your
automation setting decides how far it goes. Leave **Auto-merge** off for these
tasks: review and confirm the change yourself before it merges, so the
contribution is your work, not only the agent's.

**Steer** adds guidance, **Run next** queues a message, and **Stop this turn**
interrupts. Check **WorkBench → Changes** and the tests before **Mark it
complete**.

The sidebar’s **Working** section keeps active tasks visible. Finished tasks
stay there for eight hours after work ends, then disappear from the sidebar.
They remain available in **Tasks**.

<a id="delivery-and-ci"></a>

## Deliver changes and repair CI

The composer’s automation chip sets how far a new task goes. Each step includes
the ones before it:

- **Auto-Commit:** commits on the task’s branch. Nothing leaves your Mac.
- **Auto-Draft PR:** pushes and opens a draft PR.
- **Auto-CI fix:** reruns flaky jobs, repairs failures the PR caused, and marks
  the PR ready once checks pass.
- **Auto-Review fix:** answers review comments and pushes fixes, at most three
  rounds per thread.
- **Auto-merge:** merges after the delay in **Settings → Agents → Workflow**,
  once GitHub allows it and no review thread is open.

When the agent rewrites pushed commits, for example by splitting or squashing
them, the next push replaces them with a lease. It stops instead when the PR
branch has commits the task never had. When automation stops on an error, your
next message retries the delivery, even if that turn changes nothing.

To replace a task's GitHub PR with one from a different branch, ask the agent in
that task's conversation and give the new branch name. When your message names
the branch, ByteQuay starts at once; otherwise choose **Allow once** on the
**Replace task pull request** card after checking its PR, branch and commit.
ByteQuay copies the current PR's title, description and draft status, verifies
the replacement, connects it to the same task, then closes the previous PR.
Your conversation, working copy and enabled CI/review automation stay connected;
GitHub reviews remain on the previous PR and the replacement gets fresh checks.
Existing branches are kept. The checkout must be clean at the PR's current
commit, and queued messages must be finished or removed first. If a step fails,
ask the agent to retry with the same branch name: ByteQuay checks the saved
result and continues without blindly creating another PR. No terminal command
is needed.

Ask the agent to push the task's branch or close its PR, and ByteQuay does it
with your account, so you never run a command yourself. When your message in
that turn says push or close, it runs at once; otherwise a **Push task branch**
or **Close task pull request** card asks first. A push sends only committed work
and stops if the branch has commits the task never had. Closing keeps the
branch.

Every step after Auto-Commit writes to GitHub, and repairs use your model
allowance. The stop square above the composer halts automation and keeps your
draft. CI watching runs while ByteQuay is open and resumes after a restart.

<a id="commits-and-backports"></a>

## Cherry-pick and backport

Link related repositories once under **Related workspaces** in repository
settings. Fork links form a repo line that every workspace in it shows.
Tasks can read the code and memory of every watched repository; a link adds
guidance and lets a task start work there.

1. In **Commits → History**, pick upstream commits or a merged PR.
2. Choose **Cherry-pick**, check the **Dry run**, then **Start cherry-pick**.
   This lets ByteQuay push a work branch, open a draft PR, and fix CI.
3. Follow the task as the agent resolves conflicts, builds, and tests. Message
   it at any time.
4. When CI is green, choose **Approve squash**, then **Merge pull request**.

**Pause** holds the task; **Abort** closes the PR and deletes the work branch.
**Backport** carries a PR down the line, one cherry-pick per repository.
To ask before a PR merges, type **@backport** and its number or link in the
destination's task composer. Each step waits for its PR to merge.
Each backport PR uses the destination repository’s template and title conventions
from recently merged PRs. Without a clear convention, its title is
**Backport: _source PR title_**.

<a id="rules"></a>

## Let rules watch and act on your work

A rule names something that happens, such as a review requested from you, and
the work ByteQuay could do then. A new rule only watches: it records what it
would have done, and whether you then did that work yourself.

1. Choose **Rules** in the Assistant's composer, or open **Settings → Agents →
   Rules**. Choose **Paste a rule**.
2. Between two `---` lines, name the event (`when`), the work (`does`), and
   what you do when you agree (`agrees-when`). Write the instructions below
   them. The box shows an example.
3. Choose **Check rule** to see what it reads, then **Save rule**. It starts in
   **Observe**.

To have the Assistant write rules for you, type **@rules** in the Assistant,
alone or with what to look at, such as `@rules my reviews`. It reads the last
30 days of your work that ByteQuay keeps on this Mac: reviews, tasks you
started, and merged pull requests with their backports. It offers up to three
rules, each on a card with the data behind it. Change the rule on its card if
you want, then choose **Save rule** or **Not now**. A saved rule starts in
**Observe**.

A rule’s row shows its record, such as **12 runs · 83% agree**, and **Edit**
lists each run. A run agrees when you review the pull request, start a task,
update the branch, or open the backport in time. Records keep 30 days, and
changing the header starts a new one. The switch pauses a rule.

When a rule’s record shows you agree with it (at least 10 answered runs, 80%
agreeing), the Assistant asks whether to move it to **Suggest**. Choose **Move
to Suggest** or **Keep observing**; nothing moves up by itself. **Move down to
Observe** in the rule’s box moves it back.

In Suggest, a review rule reviews the pull request with its instructions as the
focus and keeps the findings as drafts, and an issue rule starts a task that
only commits on your Mac. The result waits in the Assistant’s **Decision**
view: **Open** it to publish, push, or continue it, or **Dismiss** it. A
finding published as written agrees; changing the work first counts as edited.
Suggestions and automatic private reviews share a limit of 3 starts per rule
and 10 total per local day. Merges and permission resolutions
are outside that budget. Skips and their reasons appear in the rule’s history.

### Activate a rule

**Active rules** are separate from **Learning and suggestions**. A good
observational record never grants permission on its own.

1. Save and check a rule, then choose **Activate…** beside it.
2. Review its event, repositories and other scope, instructions, and the action
   it authorizes. For merge and permission rules, prose notes add no enforced
   conditions. Choose **Activate rule** to authorize that saved revision.
3. Matching actions run without another approval card. Their results name the
   rule; expand its receipt to inspect each action. A summary such as **Allowed
   3 requests under Build access** records permission, not whether the work
   succeeded.
4. Pause a rule with its switch. Editing an active rule returns it to
   **Observe**; review and activate the saved revision again.

Active review rules prepare findings without posting them. A
`pull-request-ready` rule with `does: merge` can merge your own approved PR
once its checks pass; ByteQuay rechecks the current head before acting. A
`permission-requested` rule with `does: allow` needs exact `hosts` and explicit
`operations` (`network-access` or `github-read`). Requests outside that scope
still ask you. An uncertain remote outcome shows **Needs attention**, so you
can inspect it before taking another action.

With `owned-lines: true`, a rule checks the last author at the fetched merge
base, using your configured Git email or GitHub noreply identity. Only deleted
or replaced existing lines establish ownership; additions-only files and
renames do not.

<a id="issues-and-jira"></a>

## Start from a work item

Open **Work items**. **Local** items stay on your Mac; **GitHub issues** and
**Jira** show remote work.

1. Choose **Local → New** and add context and acceptance criteria.
2. **Start development** starts the agent. GitHub and Jira items open a
   prepared composer first.
3. Open the item to follow its tasks and PRs. By default it is done when its PR
   merges, or choose **Mark finished**.
4. **Push to remote…** publishes it as a GitHub issue or Jira ticket.

**Link work item** in a Local item’s **Context** references other work.

In the existing draft-publication turn, ByteQuay supplies the working agent with
a `publicPublication` flag. ByteQuay reads repository visibility using
the selected hosting account; the agent does not search GitHub or GitLab for it.
If either the push repository or PR target is public, the agent inspects outgoing
commits, files, paths, branch name and PR text for credentials and private source
information before publishing. For private or internal destinations, it skips
that publication privacy inspection. Quoted private traces use `***`; examples
use synthetic data.

This reminder runs in the existing publication turn, with no separate reviewer
or review character limit. If the agent finds an unresolved disclosure, it holds
publication and explains the issue without exposing private values. ByteQuay holds
publication if visibility is unknown or the public/non-public flag changes before
the write. Publication still requires your direct action or accepted automation
scope and passes the destination, revision and Git safety checks. Source-backed
tasks publish through ByteQuay's publication action. Replies recovered after a
restart wait for the next authorized delivery.

<a id="epics"></a>

## Organize an Epic

An Epic groups related work items and their tasks. Any Local item, GitHub issue,
or Jira ticket can be one; the grouping stays on your Mac.

1. On workspace Home, choose **Create Epic** from the Epic tab, or **Mark as
   Epic** on a work item. **Talk it through** lets an agent draft it.
2. Add work with **Add existing** or **Suggest work**.
3. Track **Planned**, **Working**, and **Done**. **Run** starts a task.
4. When a PR merges, **Next in Epic** suggests what to start next. Nothing
   starts by itself.
5. Review the outcomes, then **Conclude Epic**.

<a id="artifacts"></a>

## Learn with Artifacts

1. Open **Artifacts** and ask in **Ask about this repository**. Answers cite
   files and end with a question card. A card waits five minutes; if it times
   out, an alert opens the conversation so you can reply there.
2. Choose **Make the page** when offered, or ask for one at any time.
3. Use **Index** to jump to a page or section. Scroll down to keep reading the
   next page; the reader shows your current page and section as you go.
4. Ask for changes in the same conversation. Each page's version selector keeps
   earlier saves available without replacing the other pages in the reader.

The agent reads the repository but never changes it.

<a id="memory-and-specs"></a>

## Build repository memory

1. Open **Memory → Learn from my PRs**, or **Learn from this PR** on a closed or
   merged PR.
2. Check **Rules**, **Recipes**, and **Specs**, and edit or delete entries.
3. Choose whether **Reviews**, **Development tasks**, or both use memory.

**Memory → Check against spec** has an agent compare code with a spec and
reproduce failures.

<a id="circles"></a>

## Follow a Circle

Choose **Circles → Create a circle**, then pick people and repositories. It
sends no invitations, and **Insights** cover only what ByteQuay has collected.
Each circle belongs to one signed-in GitHub or GitLab account and holds people
from that account's server; with several accounts, pick one under **Account**.
GitLab activity comes from your watched GitLab projects.

<a id="jira"></a>

## Connect Jira

1. Open **Settings → Integrations → Jira → Add account** with your site, email,
   and API token.
2. Open **Jira**, choose a board and a sprint or backlog, then open a ticket.
3. In the ticket's **Details**, choose **Assign workspace**. The workspace's
   **Work items → Jira** lists its assigned tickets across boards, sprints,
   sites, and assignees, under **Open** or **Completed**.
4. Choose **Start development** to work on a ticket. **Refresh tickets** updates
   the workspace's saved tickets; a failed refresh keeps them visible.

Starting development moves a **To Do** ticket to **In Progress**. If Jira requires
fields or a workflow choice, change its status first and retry. After the task
publishes its PR, ByteQuay posts the PR link and a brief excerpt of its plan and
approach on Jira. Repeated pushes do not add duplicate comments.

Choose **Unlink task** beside **View task** to release the ticket for a fresh
attempt. The old task, its conversation, checkout, and PR are kept; its future
PRs no longer post to this ticket. Then choose **Start development** again.

**Open in Jira** handles anything ByteQuay doesn’t.

<a id="gmail-and-google-calendar"></a>

## Connect Gmail and Calendar

1. In **Settings → Integrations → Google Apps**, install the connector and
   **Sign in with Google**.
2. Choose **Compose** or **Reply**, then check **Review email** before **Send
   email**.
3. Save a Gemini API key to use **Ask Gemini**; it uses your key’s quota.

Mail and conversations stay in memory until you quit.

<a id="lab"></a>

## Measure with ByteQuay Lab

Choose **Settings → Integrations → Lab → Open Lab**. No terminal or Python is
needed.

- **App health** shows recent errors, send latency, and resource use, with
  suggested actions. **Analyze now** refreshes it.
- **Start resource monitoring** tracks memory and CPU while Lab is open.
  Closing the last Lab tab or quitting ByteQuay stops monitoring. It stays off
  when you reopen Lab; **Processes** can stop it sooner.
- **Agent activity** shows agent performance, review guidance, and context
  costs.
- **Improve** writes and tests new review rules on cases the Lab never saw;
  every step runs from a button.

[Explore the Lab](dist/lab.html) · [Start the Lab](dist/lab-guide.html)

## Take a companion break

Open **Settings → Personal → General → Focus breaks** and turn on **Break
reminders**. Choose **Chubby orange cat** or **Windows update** under **Break
companion**. The cat walks into ByteQuay first. When it reaches the
center, it blocks interaction with your work while your current page stays
visible behind it. The orange cat plays a smooth video: it lies down, rolls onto its
side, yawns, then sleeps with closed eyes and gentle breathing for the remainder of the break.
When the break ends, the cat wakes up and walks offscreen. Reduced motion skips
the walk and shows a still cat. If the cat video cannot play, the break uses
its resting image.

For a different screensaver, choose **Windows update** in the same picker. A
blue Windows-style update screen fills ByteQuay, with spinning dots and a
percentage that advances with your break. Reduced motion keeps the dots still.
This is a playful animation: it does not install updates or restart your Mac.

Choose your focus and break times; the default is 25 minutes of focus followed
by a 5-minute break. The break fills only the ByteQuay window, and your agents
keep working.

Use **Pause timer** or **Take a break now** in the same settings. The break
shows your selected animation and a small **Dismiss** button with the remaining
break time, such as **Dismiss · 4:59**. Choose **Dismiss** or press **Esc** to
open the confirmation. Choose **End break** to return
to work, or **Keep taking a break** to close the confirmation. During the cat's
walk out, choose **Dismiss** or press
**Esc** to return immediately. The next focus session starts when the companion leaves.

Reminders are off by default. Settings stay on this Mac; reopening ByteQuay or
changing the times starts a fresh session. Changing your companion keeps the
current timer and pause state. Focus time pauses while the window is hidden
or minimized. A due break waits until you finish an open form or confirmation.
If you open one during the walk, your companion waits before locking the app.
Switching native pages during the walk restarts the entrance over the new page
and keeps the full break time.

<a id="help"></a>

## Get help

- **GitHub unavailable:** renew your CLI sign-in or token in **Settings →
  Integrations → GitHub**.
- **Agent unavailable:** choose **Check** in **Settings → Agents → Models**.
- **Review could not finish:** use **Resume review**, or **Recover runtime**
  when shown.
- **Queue paused:** fix the cause shown, then choose **Resume queue**.
- **Delivery unconfirmed:** check GitHub, then choose **Confirmed posted** or
  **Confirmed not posted** in **Submit review**.
- **Check failed:** open **PR → Show log**, or **Open in GitHub**.

To build from source, read [CONTRIBUTING](../CONTRIBUTING.md). For product
intent, read the [design](../docs/design.md) and
[architecture](../docs/architecture.md).
