# Private KYC document verification

## Delivered

- User: clear verification progress, reviewer rejection reason, real file upload and read-only document cards. Initial submissions and rejected resubmissions are allowed; pending and approved applications are locked on the server, not just hidden in the UI.
- Admin: document previews, applicant information, timestamps, identity-check acknowledgement and a confirmation step. Rejection requires a reason that is shown to the user. Stale or already completed decisions are rejected using the application version.
- Both roles: authenticated image and PDF popups with downloads. PDFs render locally with lazy-loaded PDF.js and previous/next page controls, without an external viewer or native browser PDF dependency. Blob URLs, requests and PDF workers are released on close/navigation/session changes.
- Original bytes are stored in the workspace-root `temp_images` by `scripts/start-backend.py`, under generated names. The folder is gitignored and is never mounted as static content. Downloads check owner/admin authorization and send no-store, attachment, sandbox and no-sniff headers.
- One to four files per submission, 5 MB each; only PDF, PNG and JPEG. File signatures/image decoding, filename checks and image dimension limits supplement client validation. New files are removed if the database transaction rolls back; replaced files are removed only after a successful resubmission commits.
- No database migration/reset is required: document metadata uses the existing schema. JSON metadata-only submissions now return a clear 400 instead of pretending to upload documents.

## Compatibility and operational notes

Older submissions stored only names/sizes, not files. Their original documents cannot be reconstructed. Pending legacy submissions must be rejected with a request to upload originals; they cannot be approved without stored documents. Existing approved users are not downgraded or unlocked.

Despite the folder name, `temp_images` must be retained with the database. Do not clear it as disposable system temporary storage. This local implementation does not provide malware scanning, encryption at rest, or a regulated retention policy; deploy those controls before production use.

The frontend adds the pinned `pdfjs-dist` dependency and copies its renderer, worker, fonts, character maps and WASM assets during the normal OJET build. All assets and document processing stay local. npm reported dependency audit findings; no unrelated forced dependency upgrade was applied.

## Verification

- 103 frontend tests and TypeScript typecheck passed.
- 44 targeted backend tests passed, including a complete submit → reject → resubmit → approve lifecycle in an isolated H2 database with actual temporary file storage.
- Covered pending/approved upload rejection, owner/admin reads, foreign-user denial, anonymous denial, stale review versions, real multipart payloads, file validation and rollback cleanup.
- Browser QA used synthetic, in-memory API data: customer and admin image previews, actual file selection and resubmission, confirmation-based approval, verified read-only view, a visibly rendered PDF, and mobile layout without horizontal overflow. Browser error log was empty.
- No real customer verification decision was changed during testing.
- Live frontend/proxy and authenticated status reads were checked. An intentionally invalid multipart request was rejected with 400 before changing the user's application. The normal OJET build completed successfully.

Open `http://localhost:8000/?ojr=kyc` as a user, or Administration → Verification & routes as an administrator. Hard-refresh once after this update.
