# Product Studio — Codex implementation contract

Build a real Android application for private family use. The app must turn ordinary phone photos of products into clean ecommerce-style images on an exact #FFFFFF background, with special care for transparent glass, labels, logos, barcodes and Arabic text.

## Non-negotiable constraints
- Zero paid services and zero subscriptions.
- No cloud image API.
- No account, analytics, tracking or telemetry.
- Processing happens locally on Android after optional one-time model downloads from public open-source sources.
- Never generatively redraw the product.
- Preserve product geometry from the primary photo.
- Preserve ticket/label/logo/barcode/Arabic text pixels whenever they are opaque enough to recover from the source.
- Final background classified as background must be exact RGB(255,255,255).
- UI is in French and must be extremely simple for an older/non-technical family user.
- Build must produce an installable APK and all automated tests must pass.

## UX
Home screen has one primary button: `PRENDRE UNE PHOTO`.
After capture, app automatically chooses the best processing path. User should not need to understand models or masks.

For difficult transparent products the app may automatically capture a short exposure bracket while the user presses the shutter once. If confidence is still low, ask for an optional second or third photo with a simple guided overlay. Do not require special black or white backgrounds.

Result screen: large before/after comparison, `ENREGISTRER`, `REPRENDRE`, and a simple `CORRIGER` brush only if automatic confidence is low.

## Architecture
Native Kotlin + Jetpack Compose. Camera2/CameraX as appropriate. ONNX Runtime Android for local models. OpenCV only where it materially improves alignment/refinement. Heavy work off the main thread.

Separate modules/services for:
- capture and exposure bracketing
- image alignment
- segmentation/matting
- alpha refinement
- protected-region detection
- white-background compositing
- shadow recovery
- conservative enhancement
- quality scoring
- MediaStore export

## Open-source strategy
Use proven open-source components instead of reimplementing them. Adapt code only when its license permits it and keep required notices.

Preferred sources:
1. MRJOHN5ON/cel-android (MIT): Android ONNX Runtime background-removal architecture, model management, mask postprocessing, full-resolution export patterns.
2. LibreStatic/opencinecam (Apache-2.0): Camera2 exposure bracketing and camera capability gating. Reuse only focused camera/bracket concepts or code needed for still capture.
3. thhanns/local-background-remover (MIT) and wdvictor/bg-remove (MIT): full-resolution guided-filter refinement, alpha matting/decontamination ideas, original-RGB preservation.
4. egeorcun/lucida (MIT): preferred quality model candidate for transparent/semi-transparent objects, text/logos and glow. If a mobile-compatible ONNX version cannot be validated, fall back to BiRefNet Lite for V1 and keep Lucida behind a model interface.
5. BiRefNet ONNX/rembg ecosystem: fallback/mobile model path.

Do not import GPL components into the distributed app unless the whole project intentionally adopts compatible copyleft terms. Prefer MIT/Apache-2.0 components.

## Processing pipeline
1. Capture a high-resolution primary frame.
2. When supported, silently capture a 3-frame exposure bracket around the same geometry (for example -2EV/0/+2EV or device-supported equivalent). The user still experiences one shutter action.
3. Select/fuse detail conservatively: use the best-exposed source pixels for highlight/shadow recovery. Do not HDR-tone-map the product into an artificial look.
4. Run local segmentation/matting on a scaled analysis image.
5. Run a second crop-focused pass when confidence or edge quality is poor.
6. Upsample/refine alpha against the original full-resolution luminance using an edge-aware guided filter.
7. Detect protected opaque/high-detail regions using alpha/transmission confidence, local contrast and edge density. Protect likely labels, tickets, printed text, logos and barcodes from reconstruction/decontamination.
8. For transparent/semi-transparent pixels, preserve fractional alpha and original/refined optical detail. Never force them binary/opaque.
9. Apply foreground color decontamination only where confidence is high and never over protected regions.
10. Composite onto exact #FFFFFF.
11. Recover or synthesize only a subtle neutral contact shadow; never alter product shape.
12. Apply mild white balance, denoise and sharpening only. No hallucinated texture and no geometry change.
13. Score output quality. If confidence is below threshold, offer one simple guided second/third capture or manual restore brush instead of silently returning a bad result.

## Quality score
Combine at least:
- mask confidence / entropy
- edge continuity
- halo/fringe estimate
- foreground/background leakage estimate
- protected-region stability
- transparent-region smoothness
- alignment confidence for multi-frame captures

Never claim success when the score is below threshold.

## Tests
Add unit/instrumentation tests for:
- exact #FFFFFF background pixels
- output dimensions equal primary input dimensions unless user explicitly crops
- protected pixels remain unchanged within tolerance
- Arabic/text protection path does not OCR/redraw text
- alpha values are fractional for semi-transparent test regions
- bracket fallback works on devices without exposure compensation
- alignment rejects excessive transforms
- low-confidence result triggers review/extra-photo flow
- MediaStore save succeeds

## Delivery definition
Do not stop at scaffolding. Run Gradle build, unit tests and lint where practical. Fix build errors. Produce an APK artifact. Document any device-only camera behavior that cannot be validated in CI, and add a capability fallback rather than crashing.
