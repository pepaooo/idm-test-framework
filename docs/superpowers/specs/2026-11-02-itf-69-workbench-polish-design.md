# ITF-69: workbench file events and missing workspace message

## Intent

Close two minor findings from the ITF-56 review. A tab whose workspace has changed should promptly learn that its file event stream is over. A bookmarked report URL without workspace context should explain the missing context accurately.

## Goals and boundaries

- End a file event subscription immediately if its workspace no longer matches the session when it is registered, including a switch that occurs during registration. Keep the existing watcher behavior for subscriptions that were already registered when the workspace changes.
- When a workspace-scoped request supplies no workspace header or query parameter, return 409 with a new `ui.workspace.missing` key and a clear English or Spanish message. A report GET follows this same rule.
- Keep `ui.workspace.switched` for requests that name a different workspace. Keep `ui.workspace.none` when the session has no open workspace. Do not change report lookup, token checks, or which endpoints require workspace context.
- Avoid changes to report contents, secrets handling, or the browser's workspace-switch banner.

## Approaches considered

1. **Separate missing-workspace error (chosen).** Preserve the explicit workspace guard and distinguish absent context from a stale tab. This is a small server and translation change. A bare bookmark still cannot open a report until the user uses a workspace-qualified link.
2. **Allow a bare report GET when its run ID exists in the current workspace.** Convenient for a bookmark, but the current workspace is shared by tabs and can change. Run IDs are not a cross-workspace identity, so the same URL could show a different report.

For the file event race, rechecking the session immediately after adding the emitter is the small fix. A check only before registration would leave the original gap between that check and insertion; serializing all workspace switches and subscriptions would require a broader change to session and watcher coordination.

## Design

`FileWatcher.subscribe` retains its completion, timeout, and error callbacks. After putting the emitter in the subscriber map, it compares the requested workspace with `UiSession.workspace()`. If they differ, it removes and completes that emitter immediately. Otherwise it sends the initial `watching` comment as today. The existing watcher thread still closes registered emitters for other workspaces. Checking after insertion covers a switch that happened just before insertion; if the switch happens afterward, the watcher thread sees the registered emitter.

`WorkspaceGuard.preHandle` distinguishes an absent workspace claim from a present but nonmatching one after confirming a workspace is open. Absent claims use `runner.ui.workspace.missing`, translated into English and Spanish, with HTTP 409. Present but blank, malformed, or different claims retain the current switched response. Report GETs keep the guard; no controller exception is added. The browser marks a tab switched only for `ui.workspace.switched`, so the new error does not show a false switched banner.

## Verification

- Add a deterministic watcher test that switches the session, lets `endOthers` run, then subscribes for the old workspace and observes immediate completion; verify a subscription for the current workspace stays open.
- Extend workspace guard HTTP tests to check the new key and message for a bare report URL and another workspace-scoped endpoint, in English and Spanish. Keep the stale-workspace report test and token/report path tests.
- Run the focused runner tests, then the runner module test suite if the focused checks pass.

## Builder's calls

Use the existing Java/Spring implementation and i18n property files. The new error wording should direct the user to open the report from the workbench, without suggesting that another tab changed workspaces.
