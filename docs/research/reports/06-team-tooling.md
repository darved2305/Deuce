# Track 06: Free infrastructure and LLM toolchain

**Verdict:** Build the player app with Expo SDK 57, keep the analysis contract and demo result in the repo, and deploy a thin Cloudflare API with R2 storage. If a Python CV service is ready, try it on Modal using its advertised free compute credit. Treat that service as optional: the demo must still work offline from a locally available clip and a bundled result.

There is a cost constraint to resolve: **Claude Code is not free on Claude’s Free plan.** Codex has a $0 plan for quick coding tasks, but its allowance is limited and the exact account allowance is not a fixed public quota. If all four developers need heavy agent use, free infrastructure alone does not guarantee free model usage.

**Verified** means supported by a current source checked on 2026-10-10. **Estimated** means calculated from published rates or a vendor-reported performance figure. **UNVERIFIED** means a current public source did not establish the value, or it depends on your accounts or hardware.

## Recommended toolchain

| Area | Recommendation | Current evidence and caveats |
|---|---|---|
| Mobile app | **Expo SDK 57**, React Native 0.86, Node 22.13 or newer, Xcode 26.4 or newer. | SDK 57 is the latest stable SDK listed; SDK 58 is beta. Expo’s SDK 57 compatibility matrix lists the RN, Node, Xcode, iOS, and Android requirements. [Expo SDK releases](https://expo.dev/sdk), [SDK 57 compatibility](https://docs.expo.dev/versions/v57.0.0/), [SDK 58 beta](https://expo.dev/changelog/sdk-58-beta). |
| Expo docs for agents | Use Expo’s **`/llms.txt`** index and Markdown pages; connect Expo MCP when working on the app. | Expo MCP can inspect the project and automate simulator screenshots and taps, but requires an Expo account. Local MCP capabilities support one development-server connection at a time; iOS local automation is simulator-only on macOS. Expo says local screenshots pass through its MCP service to the agent, so use demo data in that loop. [Expo LLM docs](https://docs.expo.dev/llms/), [Expo MCP docs](https://docs.expo.dev/mcp/). |
| iOS simulator/build | Start with Apple’s Xcode agent bridge, configured through `xcrun mcpbridge`. Add **MobileBuildMCP 2.7.1** only if its CLI or workflows fill a gap. | Xcode provides its own external-agent tools. MobileBuildMCP is the renamed XcodeBuildMCP, MIT-licensed, and requires Node 18+ if installed through npm. [Apple Xcode agent access](https://developer.apple.com/documentation/Xcode/giving-external-agents-access-to-xcode), [MobileBuildMCP 2.7.1](https://github.com/getsentry/MobileBuildMCP/releases/tag/v2.7.1). |
| Cross-platform UI automation | Use **Maestro CLI 2.11.0**, Apache-2.0, for local E2E flows and its MCP server. | It supports iOS and Android flows, device control, screenshots, and MCP commands. Its `assertScreenshot` compares against a saved image and defaults to a 95% match threshold. [Maestro 2.11.0](https://github.com/mobile-dev-inc/maestro/releases/tag/cli-2.11.0), [Maestro MCP](https://docs.maestro.dev/get-started/maestro-mcp), [screenshot assertion](https://docs.maestro.dev/reference/commands-available/assertscreenshot). |
| Other mobile MCP | **Mobile MCP 1.0.9**, Apache-2.0, is an optional single interface for iOS and Android simulators, emulators, and devices. | It was released Oct 9, so smoke-test it before relying on it. I would not install it alongside every other mobile MCP by default. [Mobile MCP 1.0.9](https://github.com/mobile-next/mobile-mcp/releases/tag/1.0.9). |
| Android | Use Android Studio, the Android CLI/ADB, and Maestro. Android Studio’s Gemini agent can connect to MCP servers; for Codex or Claude Code, use Maestro MCP or Mobile MCP to control devices. | Android’s documentation covers ADB install and wireless debugging. The Android Emulator’s stable release listed Sep 29 was 37.2.11. [Android Studio MCP](https://developer.android.com/studio/gemini/add-mcp-server), [ADB](https://developer.android.com/tools/adb), [emulator releases](https://developer.android.com/studio/releases/emulator). |
| React Native debugging | Use the built-in **React Native DevTools** for JS debugging, network inspection, component inspection, and profiling. | It is part of modern React Native and is available to this Expo/RN stack. [React Native DevTools](https://reactnative.dev/docs/react-native-devtools). |
| Detox | **Detox 20.51.4**, MIT, is a valid React Native E2E alternative if someone already has it wired. | For a 24-hour build, I’d choose Maestro unless the team already knows its Detox setup. [Detox on npm](https://www.npmjs.com/package/detox). |
| Radon IDE | Do not make it the team default. | Its freeware terms restrict use to one natural person on one machine for non-commercial use. Use VS Code plus Xcode/Android Studio unless each developer has confirmed the terms fit the project. [Radon freeware terms](https://swmansion.com/legal/radon/freeware-license-terms/). |
| Monorepo tools | Pin **pnpm 12.11.2**, **Turborepo 2.11.7**, and **uv 0.13.0**. | These were the latest stable releases I found by Oct 10; do not follow canary or auto-update during the build. Expo supports pnpm workspaces, though it documents that some React Native packages can still fail with isolated installs. If Metro/native resolution fails, try pnpm’s hoisted linker. [pnpm releases](https://pnpm.io/blog?type=releases), [Turborepo releases](https://github.com/vercel/turbo/releases), [uv on PyPI](https://pypi.org/project/uv/), [Expo monorepos](https://docs.expo.dev/guides/monorepos/), [uv workspaces](https://docs.astral.sh/uv/concepts/projects/workspaces/). |
| API contract and SDKs | Use one **OpenAPI 3.0.x** contract and pin **OpenAPI Generator 7.26.0**. Generate only the clients/models the team will use. | The generator supports many client and server targets; its OpenAPI 3.1 support is still marked beta, so use 3.0.x for a one-day build. Keep generated files read-only and regenerate from the contract. [OpenAPI Generator 7.26.0](https://github.com/OpenAPITools/openapi-generator/releases/tag/v7.26.0), [project compatibility notes](https://github.com/OpenAPITools/openapi-generator/). |
| MCP server for your API | After the API contract stabilizes, make a small local stdio adapter with **`@modelcontextprotocol/server` 2.3.1**. | It is the official TypeScript SDK’s current v2 stable line. Keep its tools as thin wrappers around the same API/SDK calls. Hosted Scalar MCP is paid. [MCP TypeScript SDK 2.3.1](https://github.com/modelcontextprotocol/typescript-sdk/releases/tag/2.3.1), [Scalar pricing and licensing](https://scalar.com/solutions/openapi-documentation). |

For screenshot loops, use stable `testID`s and a fixed fixture, capture the screen with Maestro, then run `assertScreenshot`. Keep the demo screen free of timestamps and other changing text. The documented default is a 95% image match, so adjust thresholds only after comparing screenshots on the actual event device.

## ML/CV and notebook tools

| Tool | Current version and license | Use |
|---|---|---|
| PyTorch MPS | PyTorch 2.14 documentation; MPS uses Apple Metal. | Quick candidate-model experiments on an Apple Silicon Mac. Availability and speed depend on the Mac and operator support. [PyTorch MPS](https://docs.pytorch.org/docs/stable/notes/mps.html). |
| MLX | **0.32.3**, MIT. | Apple Silicon experimentation. Treat it as a Mac framework, not a shortcut to shipping the same pipeline to iOS and Android. [MLX releases](https://github.com/ml-explore/mlx/releases/tag/v0.32.3), [license metadata](https://github.com/ml-explore/mlx/blob/main/setup.py). |
| Core ML Tools | **9.0**, BSD-3-Clause. Its macOS arm64 CPython 3.11 wheel is listed as **2.64 MB**. | Consider conversion only after the chosen model works and its operations are supported. [Core ML Tools 9.0 release](https://github.com/apple/coremltools/releases/tag/9.0), [license](https://github.com/apple/coremltools/blob/main/CONTRIBUTING.md). |
| LiteRT Torch, formerly ai-edge-torch | **0.9.4**, Apache-2.0; the PyTorch converter is labeled **Beta**. | Candidate PyTorch-to-LiteRT path for Android and iOS. Validate your specific model and operators before depending on it. [LiteRT Torch 0.9.4](https://github.com/google-ai-edge/litert-torch/releases/tag/v0.9.4), [project status and license](https://github.com/google-ai-edge/litert-torch). |
| ExecuTorch | **1.5.1**, BSD license. | Another on-device PyTorch route with platform backends. It still requires model export and mobile integration work. [ExecuTorch 1.5.1](https://github.com/pytorch/executorch/releases/tag/v1.5.1), [license](https://github.com/pytorch/executorch/blob/main/LICENSE). |

**Do not implement all three conversion paths.** First run the selected model on the available Mac or phone, then test one target runtime. **UNVERIFIED:** I did not benchmark your model, video, or target devices. Phone inference speed, conversion success, and model-file size are unknown until that specific combination is tested.

For notebook work, the current **Google Colab CLI is 0.7.4**, Apache-2.0, Python 3.12+, with Linux and macOS support. It runs local scripts or notebooks on a Colab runtime, but free GPU allocation and runtime limits are dynamic; the free service is not a dependable deployment target. [Colab CLI on PyPI](https://pypi.org/project/google-colab-cli/), [Colab free-tier FAQ](https://research.google.com/colaboratory/faq.html).

The current **Kaggle CLI is 2.2.4**. It can push a notebook/script, inspect run status, fetch logs, and download output. Kaggle documents a baseline of **30 GPU hours per week**, sometimes higher based on demand and resource availability. Use it for optional evaluation, not the live app. [Kaggle CLI release](https://github.com/Kaggle/kaggle-cli/releases/tag/v2.2.4), [kernel commands](https://github.com/Kaggle/kaggle-cli/blob/main/docs/kernels.md), [GPU quota guidance](https://www.kaggle.com/docs/efficient-gpu-usage).

## LLM-agent access

| Service | Free access checked | Practical implication |
|---|---|---|
| Codex | The official pricing page lists a **$0 Free** plan for quick coding tasks. It also says current limits are visible in the account’s usage dashboard. | Useful for small bounded work. **UNVERIFIED:** exact allowance for each team account and whether it can sustain 24-hour agent work. [OpenAI pricing](https://learn.chatgpt.com/docs/pricing). |
| Claude Code | The Claude Free plan does not include Claude Code. The individual Pro plan is **$20/month billed monthly**; Max starts at **$100/month**. | Count Claude Code as paid unless the team already has valid subscriptions and treats them as sunk cost. [Anthropic pricing](https://www.anthropic.com/pricing), [Claude Code plan access](https://support.anthropic.com/en/articles/11145838-using-claude-code-with-your-pro-or-max-plan). |
| GitHub Student Developer Pack | Verified students can get GitHub Pro and Copilot Student. Student Copilot has unlimited completions but limited chat/agent use and an AI-credit allowance. The pack also lists Pro-level Codespaces access, an Appwrite Education offer, and a Camber Student plan. | Useful backup if activated before the event. Camber is listed with **40 CPU hours, 5 GPU hours, 50 GB storage, and 50 agent messages per month**. **UNVERIFIED:** each member’s eligibility, approval timing, current individual Copilot credit allowance, and Codespaces quota. [Student Pack](https://education.github.com/pack), [student eligibility](https://education.github.com/pack/join). |

The SDK, MCP servers, CLIs, and local editors do not cover LLM inference by themselves. Do not build the team plan around a free agent allowance that nobody has checked in their account.

## Free hosting and service limits

All amounts below are in USD where applicable. These are published limits, not tested account quotas.

| Service | Verified free allowance | Fit for this project |
|---|---|---|
| **EAS Build** | $0 plan includes up to **15 iOS and 15 Android builds/month**, **one concurrent build**, low-priority queue, **45-minute timeout**, and **60 CI/CD minutes**. | Use for a few shareable builds, not frequent iteration. Build locally where possible and reserve remote builds. [EAS pricing](https://expo.dev/pricing), [billing FAQ](https://docs.expo.dev/billing/faq/). |
| **GitHub Actions** | GitHub Free private repositories get **2,000 minutes/month**, **500 MB artifact storage**, and **10 GB cache per repository**. Public repositories’ standard hosted runner use is free. Listed macOS runner price is **$0.062/minute**, versus **$0.006/minute** for a Linux 2-core runner. | Keep CI Linux-first. macOS runner minutes are roughly **10.3×** the Linux rate by calculation, so reserve them for cases that need Xcode. [GitHub Actions billing](https://docs.github.com/en/billing/concepts/product-billing/github-actions), [runner rates](https://docs.github.com/en/billing/reference/actions-runner-pricing). |
| **Cloudflare Workers** | Free: **100,000 requests/day**, **10 ms CPU per request**, **128 MB memory**, **50 subrequests/request**. | Good for thin routing, job metadata, and signed upload URLs. The 10 ms CPU cap rules it out for video decoding or CV inference. [Workers limits](https://developers.cloudflare.com/workers/platform/limits/). |
| **Cloudflare R2** | Standard storage: **10 GB-month**, **1 million Class A** and **10 million Class B requests/month**; internet egress is free. The free tier does not apply to Infrequent Access storage. | Good for a small number of uploaded clips and results; keep the 10 GB cap in view. [R2 pricing](https://developers.cloudflare.com/r2/pricing/). |
| **Cloudflare D1** | Free: **5 million rows read/day**, **100,000 rows written/day**, **5 GB total storage**. | Good for demo/job metadata. Index query columns because reads count scanned rows. [D1 pricing](https://developers.cloudflare.com/d1/platform/pricing/). |
| **Cloudflare Pages** | Free: **500 builds/month**, one concurrent build, **20-minute** build timeout. | Fine for static API docs or a developer landing page. Keep it outside the phone demo path. [Pages limits](https://developers.cloudflare.com/pages/platform/limits/). |
| **Supabase** | Free: **50,000 MAU**, **500 MB database**, **5 GB egress**, **5 GB cached egress**, **1 GB file storage**, and **2 active projects**. Projects pause after one week of inactivity. | A quick Auth/Postgres alternative, but do not use its free object storage for match video. [Supabase pricing](https://supabase.com/pricing). |
| **Neon** | Free plan: **100 projects**, each with **1 GB Postgres**, **100 compute-unit hours/month**, up to **2 CU**, 10 branches, and **5 GB object storage**. | Better if the team wants Postgres and does not need a Cloudflare-only stack. The 1 GB database allowance was updated Oct 2, 2026. [Neon free-plan update](https://neon.com/blog/neon-free-plan-1-gb-per-project). |
| **Turso** | Free: **100 databases**, **5 GB storage**, **500 million row reads/month**, **10 million row writes/month**, and **3 GB monthly sync**. | Worth considering for a local-first SQLite design. Avoid adding it if D1 already covers the demo. [Turso pricing](https://turso.tech/pricing). |
| **Modal** | Starter is $0 and advertises **$30/month free compute credit**. Published GPU rates include L4 at **$0.000222/second** and T4 at **$0.000164/second**, plus CPU and memory charges. | Optional Python/CV worker, not guaranteed free inference. **Estimated:** the credit buys about 37.5 GPU-only hours on L4 or 50.8 on T4 before CPU and memory charges; actual runtime is lower. [Modal pricing](https://modal.com/pricing). |
| **Hugging Face Spaces** | Static Spaces are free. Free personal accounts in good standing can host up to **2 ZeroGPU Spaces**, with **5 GPU minutes/day**; the account must be at least 30 days old with verified email. Regular Gradio/Docker compute Spaces require a paid plan. | Static API docs are possible; not a dependable free CV backend. [Spaces overview](https://huggingface.co/docs/hub/en/spaces-overview), [ZeroGPU limits](https://huggingface.co/docs/hub/en/spaces-zerogpu). |
| **Vercel Hobby** | $0, with **1 million function invocations**, **4 active CPU hours**, **360 GB-hours provisioned memory**, and **100 GB-hours function duration** listed. Hobby is restricted to personal, non-commercial use. | Do not make it the default for a product competition unless the team has verified that the project fits the terms. [Vercel Hobby plan](https://vercel.com/docs/plans/hobby). |
| **Netlify Free** | **300 credits/month**. When the limit is reached, projects pause; Netlify states all projects on the account can pause if one exceeds its limit. | Avoid for the demo’s primary endpoint. [Netlify pricing](https://www.netlify.com/pricing/). |
| **Mintlify** | Free Starter: **5 editor seats**, custom domain, web editor, auth, MCP server, and API playground. | Convenient hosted docs for four developers, but those docs still need internet. [Mintlify pricing](https://www.mintlify.com/pricing). |
| **Scalar** | Free hosted docs cover an API of up to **25 endpoints** and one SDK. The renderer and API client are MIT-licensed and self-hostable. Hosted MCP servers and Git sync are Pro features, listed at **$150/month**. | Self-host its renderer on Pages; use the local MCP SDK above instead of paid hosted MCP. [Scalar pricing and license](https://scalar.com/solutions/openapi-documentation). |
| **Sentry** | Sentry says the legacy Developer plan remains free and its error/replay quotas are unchanged. Its help page lists **5 GB logs/month**. **UNVERIFIED:** exact current free error and replay quotas are not shown in the latest public plan-change page. | Optional for errors only. Avoid spending setup time unless an account already exists. [Sentry plan changes](https://www.sentry.help/en/articles/16738709-changes-to-legacy-developer-plans-september-2026), [logs allowance](https://www.sentry.help/en/articles/13965120-can-i-create-informational-events-that-wont-count-as-errors). |
| **PostHog** | Free product analytics: **1 million events/month**, no credit card required; its current product page lists one project. | Optional product analytics. Do not upload video, user identifiers, or sensitive match data in event properties. [PostHog pricing](https://posthog.com/product-analytics/pricing). |

## Offline deployment split

The free tiers suggest this split:

1. **Phone:** gallery picker, local clip playback, match navigation, cached results, and the bundled demo result. Package the demo clip/result before the event. This is the guaranteed offline path.
2. **Cloudflare:** a thin TypeScript API for job creation/status and metadata; R2 for uploaded clips; D1 for small job records.
3. **Python CV:** optional Modal worker behind the API. Submit a short clip and poll for its result. Keep this outside the critical demo path because free credits, network, account access, cold starts, and inference time are not guaranteed.
4. **Developer surface:** the OpenAPI contract, generated SDK, static Scalar reference, and local stdio MCP adapter all use the same API schema.

This does **not** establish that full arbitrary-video analysis runs offline on a phone. Model speed, model size, and conversion support remain **UNVERIFIED** until tested on the actual devices with the chosen model. Show the sample result offline; label any cloud-generated result as live only when it was actually produced.

## Device logistics

- **iPhone:** Xcode’s free Personal Team provisioning can install apps on personal devices without a paid Apple Developer membership. The free provisioning limit includes **10 App IDs**, **3 devices**, and provisioning that expires after **7 days**. Plan to rebuild/reinstall if the event falls after expiry. This is not a replacement for App Store distribution. [Apple developer account details](https://developer.apple.com/help/account/basics/about-your-developer-account/).
- **Android:** build an APK and install with ADB. Android 11+ supports wireless debugging and pairing, but USB is a safer offline fallback. [Android ADB](https://developer.android.com/tools/adb), [install/run on a device](https://developer.android.com/studio/run/device.html).
- **Mirroring:** Android uses **scrcpy 5.0.1**, Apache-2.0, over USB or TCP/IP with no on-device app or internet requirement. Its project reports 30–120 fps and 35–70 ms latency depending on device; those are vendor figures, not measurements on your hardware. [scrcpy 5.0.1](https://github.com/Genymobile/scrcpy/releases/tag/v5.0.1).
- **iPhone projector:** use a compatible USB-C/Lightning-to-HDMI adapter, or cable the phone to a Mac and display its QuickTime device preview on the projector. Test the exact cable, adapter, projector input, and phone ahead of time. [Apple wired display guide](https://support.apple.com/guide/iphone/connect-to-a-display-with-a-cable-iph93af723b/ios), [QuickTime device capture](https://support.apple.com/en-mide/guide/quicktime-player/qtp356b55534/mac).

## Repo skeleton and parallel work

```text
elevate/
├── AGENTS.md
├── CLAUDE.md -> AGENTS.md
├── package.json
├── pnpm-workspace.yaml
├── turbo.json
├── pyproject.toml
├── uv.lock
├── contracts/
│   ├── openapi.yaml
│   └── fixtures/
├── apps/
│   ├── player/          # Expo mobile app
│   ├── api/             # thin Cloudflare Worker
│   └── docs/            # static Scalar API reference
├── services/
│   └── vision/          # Python CV service
├── packages/
│   ├── sdk-ts/          # generated from OpenAPI
│   ├── mcp/              # thin local stdio MCP server
│   └── ui/
├── fixtures/
│   ├── demo/
│   └── expected/
├── e2e/maestro/
├── eval/
└── .github/workflows/
```

Use one canonical `contracts/openapi.yaml`. Include a health endpoint, the analysis job request/status shape, and one real demo response fixture. Generate bindings; never hand-edit generated clients. Python’s `uv` workspace gives the Python service a shared lockfile. Expo documents pnpm workspace support; if a React Native package fails with isolated dependencies, switch to the documented hoisted option. [Expo monorepos](https://docs.expo.dev/guides/monorepos/), [uv workspaces](https://docs.astral.sh/uv/concepts/projects/workspaces/).

Give each developer a worktree and a clear owner:

- **Mobile:** player screens, gallery flow, playback, and offline fixture.
- **CV:** candidate inference, evaluation script, and measured results.
- **Platform:** API, contract, generated SDK, docs, and MCP adapter.
- **Integration/demo:** owns lockfiles and generated output, runs builds, Maestro, phone checks, and projector fallback.

Freeze the contract early. Other lanes code against fixtures until the endpoints are ready. Keep `AGENTS.md` short and put package-specific rules close to the package. Let `CLAUDE.md` point to the same instructions instead of maintaining two copies. Merge small changes often; have one person handle root lockfile regeneration.

## Hour 0 to 2 setup checklist

| Time | Work |
|---|---|
| **0:00–0:15** | Inventory Macs, Apple Silicon, Xcode, Android SDK, real test phones, USB cables, adapters, and projector inputs. Confirm student benefits and agent-account limits before relying on them. |
| **0:15–0:30** | Create the repo and worktrees. Pin Node 22.13+, pnpm 12.11.2, Turbo 2.11.7, and uv 0.13.0. Add the concise `AGENTS.md`/`CLAUDE.md` setup and create the package skeleton. |
| **0:30–0:50** | Freeze the first OpenAPI contract and fixture. Generate the TypeScript client and Python model/client bindings. Get one health endpoint working locally. |
| **0:50–1:15** | Create the Expo SDK 57 player and open it in an iOS simulator and Android emulator. Render the local fixture with no API dependency. |
| **1:15–1:40** | Run one Maestro flow through the main demo path. Save one stable screenshot baseline. Install on at least one physical device and try the real gallery clip. |
| **1:40–2:00** | Test the demo with internet disabled. Mirror both device options that the team may use. Deploy the thin Worker if accounts are ready; otherwise, keep the same API shape local and continue. Record any model inference speed only with device, clip, and settings attached. |

## Recommendation

Use **Expo SDK 57 + Maestro + pnpm/Turbo/uv + OpenAPI Generator**, with Cloudflare Workers/R2/D1 for the thin API and Modal only as an optional Python inference target. Ship an offline sample path with the result already available on the phone. Do not claim full offline CV until the exact model has passed a real-device test.

## Three biggest risks

1. **Agent usage is not uniformly free.** Claude Code requires a paid individual plan; Codex Free is limited and account-specific.
2. **iOS provisioning and hardware can block the build.** The free Apple path is temporary and device-limited; the team needs a working Mac/Xcode or enough time and connectivity for EAS.
3. **Live cloud analysis can fail at the venue.** Workers cannot run CV, Modal credits may not cover the workload, and notebook GPUs are quota-limited. The offline fixture is necessary.

## Open questions for the team

- Which Macs, phones, OS versions, USB cables, and projector adapters will be available at the event?
- Which developers already have usable Claude Code or Codex allowances, and has anyone activated GitHub Education benefits?
- Is the demo judged on arbitrary live analysis of a new video, or can the primary offline demonstration use a prepared permitted clip and cached analysis result?