# KanDong region visual lab acceptance — completed from 5fa799a

Bounded next item; COMPLEX maximum3 specialist steps. AO audit→implementation→independent review; root builds and verifies. No production modules, external service, real screen, phone, model/dependency change, auto consent, push or release. Prefer test-only activity if standalone class closure permits; packaging decision pending audit. Dedicated AVD only.

Visible workflow: static whole page with readable single-language fixture, independent free-width/height red ROI draggable inside/border, upper-right24dp marker with48dp hit target; mirror canvas position projections and source-linked raw text/empty/conflict cards. Default zoom2.0, continuous1–5 slider, mirror pinch sync, mirror single-finger pan. No Move icon. All fixed sample material identified as sample, no claim of new OCR/translation or screen evidence. Zoom value relative to source preview vs source pixel mapping must be labelled/implemented consistently.

Explicit show-sample action only installs retained whole evidence; no auto apply at launch/page change/restore. All candidates retained incl outsideROI context. Selected IDs separate mirror clipped IDs. Concurrent/conflicting candidates presented together, no best-text selection or overlapping illegible text. Offer two fixtures or empty fixture to demonstrate switching and clear. No mixed-language test requirement.

Collapse/menu/background/stop/pagechange/60s TTL clears active Frame and text Views/canvas refs; restore keeps ROI/scale/pan but requires explicit action. Full-screen menu with return preserving collapsed vs expanded. Activity rotation/stop destroys session and never auto restores. Invalid frame/geometry cannot leave old text; geometry input clamped. Sample fixture constants aren't private captured data; disclose reference lifetime.

Tests: pure host fixture/model behavior + meaningful gestures/state; actual emulator UI tests using real view coordinates, stable accessible labels, screenshots review. Protect independently resized ROI without zoom change, slider/pinch sync, panning, page/collapse/menu restore and monotonic expiry with actual timer. Test package/launcher and sourceSet boundary identity. Run existing modelprobe JVM/build/Lint + focused emulator regression and actual visual inspection at portrait default; add small-layout or landscape probe only if change creates unresolved concern. Do not call UI validated solely from controller tests.

Document packaging, exact launch command, test counts/artifacts, user-visible scope, known limits, localcommit. Leave next task concrete.

## Audited packaging decision

Use a Java-only debug host Activity in modelprobe and test-APK Surface loaded via a fixed, same-signature package. No new module/dependency, no sourceSet duplication; parent host supplies Kotlin runtime omitted from instrumentation APK. Instrumentation uses the existing class loader; standalone uses PathClassLoader. Main debug experiment APK identity changes; formal app/compat/graphics do not. No external Intent payload is accepted. Missing or incompatible package has explicit local error, no installation fallback. Verify standalone cold launch and real UI before acceptance.

Use fixed 1200x800 Traditional Chinese synthetic page and blank page, not historical OCR. Synthetic receipts/provenance exercise the contract only, never claim real inference. Both panes share preview scale s: positions multiply by s, mirror by s*zoom; mirror viewport dimensions passed to controller divided by s. The displayed zoom is relative to the preview. Root writes nine behavioral red tests before implementation.

## Accepted evidence

217 JVM checks and offline build/lint passed (0 errors,4 existing warnings). Dedicated API37/16KiB emulator59 checks passed, including7UI and real60s expiry. Cold standalone launch, real tap, landscape/menu, rotation reset, missing test-package error and reinstalled pair verified. Original9 red methods retained; root edge bug red preserved. Independent review found1test precondition error, corrected by growing above minimum before shrink; implementation hashes unchanged since review, final59passed. Only debug host changes main experimentAPK; no phone/product/network/newOCR/translation. See ../OCR_REGION_VISUAL_LAB.md and ../evidence/region-visual-lab/2026-09-30/summary.json.
