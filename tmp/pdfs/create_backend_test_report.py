from reportlab.lib import colors
from reportlab.lib.enums import TA_CENTER, TA_LEFT
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle
from reportlab.lib.units import mm
from reportlab.platypus import (SimpleDocTemplate, Paragraph, Spacer, Table,
                                TableStyle, PageBreak, KeepTogether)
from reportlab.pdfbase.pdfmetrics import stringWidth
from pathlib import Path

OUT = Path('output/pdf/FluxPay_M1_Backend_Test_Report.pdf')
OUT.parent.mkdir(parents=True, exist_ok=True)

NAVY = colors.HexColor('#102A43')
BLUE = colors.HexColor('#1D70B8')
TEAL = colors.HexColor('#087E8B')
PALE = colors.HexColor('#EAF3FA')
LIGHT = colors.HexColor('#F6F8FA')
GREEN = colors.HexColor('#137333')
AMBER = colors.HexColor('#8A5A00')
RED = colors.HexColor('#B3261E')
GRAY = colors.HexColor('#52606D')

styles = getSampleStyleSheet()
styles.add(ParagraphStyle(name='Title2', parent=styles['Title'], fontName='Helvetica-Bold',
    fontSize=28, leading=33, textColor=NAVY, alignment=TA_LEFT, spaceAfter=8))
styles.add(ParagraphStyle(name='Subtitle', parent=styles['Normal'], fontName='Helvetica',
    fontSize=13, leading=18, textColor=GRAY, spaceAfter=18))
styles.add(ParagraphStyle(name='H1x', parent=styles['Heading1'], fontName='Helvetica-Bold',
    fontSize=18, leading=23, textColor=NAVY, spaceBefore=8, spaceAfter=9))
styles.add(ParagraphStyle(name='H2x', parent=styles['Heading2'], fontName='Helvetica-Bold',
    fontSize=12.5, leading=16, textColor=BLUE, spaceBefore=8, spaceAfter=6))
styles.add(ParagraphStyle(name='Bodyx', parent=styles['BodyText'], fontName='Helvetica',
    fontSize=9.4, leading=13.4, textColor=colors.HexColor('#243B53'), spaceAfter=5))
styles.add(ParagraphStyle(name='Small', parent=styles['BodyText'], fontName='Helvetica',
    fontSize=8.1, leading=10.5, textColor=colors.HexColor('#334E68')))
styles.add(ParagraphStyle(name='Tiny', parent=styles['BodyText'], fontName='Helvetica',
    fontSize=7.2, leading=9.0, textColor=colors.HexColor('#334E68')))
styles.add(ParagraphStyle(name='Callout', parent=styles['BodyText'], fontName='Helvetica-Bold',
    fontSize=10.2, leading=14, textColor=NAVY, leftIndent=7, rightIndent=7, spaceBefore=4, spaceAfter=4))

def P(text, style='Bodyx'):
    return Paragraph(text, styles[style])

def table(rows, widths, header=True, font=7.7):
    converted = [[P(str(cell), 'Tiny' if font <= 7.4 else 'Small') for cell in row] for row in rows]
    t = Table(converted, colWidths=widths, repeatRows=1 if header else 0, hAlign='LEFT')
    commands = [
        ('VALIGN', (0,0), (-1,-1), 'TOP'),
        ('GRID', (0,0), (-1,-1), 0.35, colors.HexColor('#C7D3DF')),
        ('LEFTPADDING', (0,0), (-1,-1), 5), ('RIGHTPADDING', (0,0), (-1,-1), 5),
        ('TOPPADDING', (0,0), (-1,-1), 4), ('BOTTOMPADDING', (0,0), (-1,-1), 4),
        ('BACKGROUND', (0,1), (-1,-1), colors.white),
    ]
    if header:
        commands += [('BACKGROUND', (0,0), (-1,0), NAVY), ('TEXTCOLOR', (0,0), (-1,0), colors.white)]
    for r in range(2 if header else 1, len(rows), 2):
        commands.append(('BACKGROUND', (0,r), (-1,r), LIGHT))
    t.setStyle(TableStyle(commands))
    return t

def badge(text, color):
    t = Table([[P('<font color="white"><b>%s</b></font>' % text, 'Small')]], colWidths=[37*mm], rowHeights=[9*mm])
    t.setStyle(TableStyle([('BACKGROUND',(0,0),(-1,-1),color), ('VALIGN',(0,0),(-1,-1),'MIDDLE'),
                           ('ALIGN',(0,0),(-1,-1),'CENTER'), ('BOX',(0,0),(-1,-1),0,color)]))
    return t

def header_footer(canvas, doc):
    canvas.saveState()
    w, h = A4
    canvas.setStrokeColor(colors.HexColor('#D9E2EC'))
    canvas.line(18*mm, h-14*mm, w-18*mm, h-14*mm)
    canvas.setFont('Helvetica-Bold', 8)
    canvas.setFillColor(NAVY)
    canvas.drawString(18*mm, h-11*mm, 'FLUXPAY')
    canvas.setFont('Helvetica', 7.5)
    canvas.setFillColor(GRAY)
    canvas.drawRightString(w-18*mm, h-11*mm, 'M1 Backend Test Report')
    canvas.line(18*mm, 14*mm, w-18*mm, 14*mm)
    canvas.setFont('Helvetica', 7.4)
    canvas.drawString(18*mm, 9*mm, 'Internal project testing documentation')
    canvas.drawRightString(w-18*mm, 9*mm, 'Page %d' % doc.page)
    canvas.restoreState()

story = []
story += [Spacer(1, 22*mm), P('FluxPay M1', 'Title2'), P('Backend Testing Report', 'Title2'),
          P('Authentication, user profile, KYC, administration, security, and Oracle verification', 'Subtitle')]
story += [Spacer(1, 7*mm), badge('TESTED - PASS', GREEN), Spacer(1, 9*mm)]
cover = [
    ['Report status', '<b>Completed</b> - all executed automated tests passed'],
    ['Test execution date', '11 September 2026'],
    ['Application under test', 'FluxPay M1 backend (Spring Boot, Oracle, JWT)'],
    ['Test approaches', 'Postman API checks, unit tests, MockMvc controller tests, Oracle integration test'],
    ['Automated result', '<b>42 tests run; 0 failures; 0 errors; 0 skipped</b>'],
]
story += [table(cover, [45*mm, 120*mm], header=False, font=8.2), Spacer(1, 10*mm),
          P('<b>Purpose.</b> This document records what was tested, the expected and observed behaviour, the test data used, and defects identified and resolved during the M1 backend test phase.', 'Callout')]
story.append(PageBreak())

story += [P('1. Executive summary', 'H1x'),
          P('The FluxPay M1 backend was tested successfully using manual API verification in Postman and a repeatable automated test suite. The verified scope covers authentication, user self-service, KYC submission and lifecycle handling, administrator review, JWT security responses, wallet provisioning integration, and Oracle persistence.', 'Bodyx'),
          P('The backend is suitable to move forward from the backend test phase. The dedicated Oracle test schema remains available for future repeatable integration runs.', 'Callout'),
          P('Test outcome', 'H2x')]
summary_rows = [
    ['Area', 'Result', 'Evidence'],
    ['Backend startup and database', '<font color="#137333"><b>PASS</b></font>', 'Oracle connection established; Flyway applied migrations through V502.'],
    ['Manual API testing', '<font color="#137333"><b>PASS</b></font>', 'Nine endpoints and negative/security flows exercised with Postman.'],
    ['Automated test suite', '<font color="#137333"><b>PASS</b></font>', '42 passed; 0 failed; 0 errors; 0 skipped.'],
    ['Oracle integration test', '<font color="#137333"><b>PASS</b></font>', 'Dedicated FLUXPAY_TEST schema; migration, API flow, repository verification, cleanup.'],
    ['Open issues blocking backend', '<font color="#137333"><b>NONE</b></font>', 'Issues found during testing were resolved and verified.'],
]
story += [table(summary_rows, [46*mm, 29*mm, 90*mm]), P('Environment and configuration', 'H2x')]
env_rows = [
    ['Component', 'Configuration used'],
    ['Backend profile', 'local,m1-solo'],
    ['Application URL', 'http://localhost:8080'],
    ['Database', 'Oracle Free PDB (FREEPDB1)'],
    ['Main application schema', 'FLUXPAY'],
    ['Automated integration schema', 'FLUXPAY_TEST (dedicated test-only user)'],
    ['Wallet integration', 'M1 mock wallet provisioner, active only under m1-solo'],
    ['API contract check', 'OpenAPI available at GET /v3/api-docs'],
]
story += [table(env_rows, [50*mm, 115*mm])]
story.append(PageBreak())

story += [P('2. Manual API testing - Postman', 'H1x'),
          P('The following checks were executed against a running local backend with an Oracle database. The table records representative API data; authentication tokens and passwords are intentionally not included in this report.', 'Bodyx')]
api_rows = [
    ['Endpoint / scenario', 'Expected result', 'Observed result'],
    ['GET /v3/api-docs', 'OpenAPI document returned', 'PASS - OpenAPI 3.0.1 document returned.'],
    ['POST /api/auth/register', '201; USER role; kycStatus NONE; JWT envelope', 'PASS - registered m1.user03@fluxpay.test as USER with KYC NONE.'],
    ['Duplicate canonical registration', '409 EMAIL_EXISTS', 'PASS - padded/mixed-case duplicate rejected.'],
    ['POST /api/auth/login', '200, one-hour JWT', 'PASS - tokenType Bearer; expiresIn 3600.'],
    ['Invalid credentials', '401 INVALID_CREDENTIALS; no account disclosure', 'PASS - correct generic response.'],
    ['GET /api/users/me', '200 current user only', 'PASS - user profile and derived KYC status returned.'],
    ['PUT /api/users/me', '200; fullName change only', 'PASS - name updated; protected fields remained unchanged.'],
    ['Missing / invalid JWT', '401 AUTH_REQUIRED', 'PASS - both flows returned the JSON security envelope.'],
    ['Padded/mixed-case legacy login', 'Canonicalized login succeeds', 'PASS - after validation correction, login returned 200.'],
]
story += [table(api_rows, [52*mm, 55*mm, 58*mm])]
story += [P('KYC user API checks', 'H2x')]
kyc_rows = [
    ['Scenario', 'Representative test data', 'Observed result'],
    ['Initial status', 'New user with no KYC case', 'PASS - 200; status NONE; applicationId/version null.'],
    ['Empty submission', 'No document metadata', 'PASS - 400 VALIDATION (doc fields and documents required).'],
    ['Valid submission', 'PAN ABCDE1234F; pan-card.pdf; application/pdf; 102400 bytes', 'PASS - 201; status PENDING; version 0.'],
    ['Pending re-submit', 'Second valid submission while PENDING', 'PASS - 409 KYC_ALREADY_PENDING.'],
    ['Unsupported file type', 'fileType application/octet-stream', 'PASS - 400 VALIDATION.'],
    ['Oversized file', 'fileSize over 5 MiB', 'PASS - 400 VALIDATION.'],
    ['Verified re-submit', 'Valid submission after approval', 'PASS - 409 KYC_ALREADY_VERIFIED.'],
]
story += [table(kyc_rows, [47*mm, 63*mm, 55*mm])]
story.append(PageBreak())

story += [P('3. Admin KYC-review and state transition testing', 'H1x'),
          P('Admin operations were tested using a separately authenticated ADMIN account. A USER token was also deliberately used against the admin list endpoint to verify role enforcement.', 'Bodyx')]
admin_rows = [
    ['Scenario', 'Expected', 'Observed result'],
    ['USER calls GET /api/admin/kyc/applications', '403 FORBIDDEN', 'PASS - USER-to-admin denial verified.'],
    ['ADMIN lists PENDING applications', '200 with pending KYC row', 'PASS - user, PAN metadata, documents, status and version returned.'],
    ['Reject without a reason', '400 REJECT_REASON_REQUIRED', 'PASS - blank reason rejected.'],
    ['Reject with reason', '200; status REJECTED; version increments', 'PASS - reason persisted; version changed 0 to 1.'],
    ['Rejected user re-submits', '201; PENDING; decision fields reset', 'PASS - version changed 1 to 2; reason and decision time cleared.'],
    ['Stale approve', '409 KYC_CONFLICT', 'PASS - outdated expectedVersion rejected.'],
    ['Approve current PENDING case', '200; VERIFIED; reviewer/timestamp/version updated', 'PASS - version changed 2 to 3; KYC VERIFIED.'],
    ['User status after approval', 'VERIFIED returned in profile/status', 'PASS - GET /api/kyc/my-status and /api/users/me confirmed VERIFIED.'],
]
story += [table(admin_rows, [53*mm, 55*mm, 57*mm])]
story += [P('Verified lifecycle evidence', 'H2x')]
life_rows = [
    ['Stage', 'Status', 'Version', 'Important observed data'],
    ['No application', 'NONE', 'null', 'No application id, submission time, or decision time.'],
    ['First submission', 'PENDING', '0', 'PAN document metadata accepted.'],
    ['Admin rejection', 'REJECTED', '1', 'Reason: Document image needs to be clearer.'],
    ['Resubmission', 'PENDING', '2', 'Rejection fields reset.'],
    ['Admin approval', 'VERIFIED', '3', 'Decision timestamp populated; status derived correctly in profile.'],
]
story += [table(life_rows, [38*mm, 30*mm, 25*mm, 72*mm])]
story.append(PageBreak())

story += [P('4. Automated test evidence', 'H1x'),
          P('Automated tests were executed with Maven against the backend project. The complete suite result was: <b>Tests run: 42, Failures: 0, Errors: 0, Skipped: 0; BUILD SUCCESS.</b>', 'Bodyx')]
auto_rows = [
    ['Test class / layer', 'Tests', 'Coverage verified'],
    ['JwtUtilTest', '3', 'Valid signed JWT, tampered token, expired token.'],
    ['M1ControllerMvcTest', '16', 'All nine endpoints; success paths; authorization and key error contracts.'],
    ['OracleM1ApiIntegrationTest', '1', 'Real Oracle schema migration, registration, JWT-authenticated profile, KYC persist/read and cleanup.'],
    ['AuthServiceTest', '6', 'Canonical registration, BCrypt hash, wallet provision, duplicate handling, padded login, credentials, KYC state.'],
    ['DatabaseKycGateTest', '4', 'Gate true only for VERIFIED; other states denied.'],
    ['KycServiceTest', '8', 'Submit validation, NONE/PENDING/REJECTED/VERIFIED lifecycle and conflicts.'],
    ['UserServiceTest', '4', 'Current-user reads, permitted name update, and KYC-status derivation.'],
    ['Total', '<b>42</b>', '<b>All passed</b>'],
]
story += [table(auto_rows, [55*mm, 22*mm, 88*mm])]
story += [P('Oracle integration verification', 'H2x'),
          P('A separate FLUXPAY_TEST user/schema was created to avoid altering the application data. Flyway applied the full migration history through V502 successfully. The integration test used this schema to create a unique user and KYC application through the actual HTTP layer, verified persistence through repositories, and removed the transient test data after execution.', 'Bodyx'),
          P('<b>Repeat command:</b> Set ORACLE_TEST_JDBC_URL, ORACLE_TEST_USERNAME and ORACLE_TEST_PASSWORD for the dedicated test schema, then run <font name="Courier">.\\mvnw.cmd -f backend\\pom.xml test</font>. The backend server does not need to be running for this command.', 'Bodyx')]
story.append(PageBreak())

story += [P('5. Issues found and resolved', 'H1x'),
          P('The following issues were identified during test preparation or execution. Each was corrected and then retested.', 'Bodyx')]
issues_rows = [
    ['ID', 'Issue found', 'Resolution', 'Verification'],
    ['T-01', 'Padded/mixed-case email login failed request validation before canonicalization.', 'Removed strict email-format validation from LoginRequest; retained required/length validation so AuthService can trim and lowercase the legacy email value.', 'Postman login using padded/mixed-case input returned 200 and the canonical account.'],
    ['T-02', 'Authenticated admin requests initially returned AUTH_REQUIRED while testing rejection.', 'Corrected the request route to the implemented endpoint: /api/admin/kyc/applications/{id}/reject. JWT filter registration was also made explicit in the security chain.', 'Admin blank-reason request returned expected 400; valid reject returned 200.'],
    ['T-03', 'Dedicated Oracle test schema migration failed because the schema lacked required identity/sequence privileges.', 'Recreated FLUXPAY_TEST with quota and CREATE SESSION, CREATE TABLE, CREATE TRIGGER and CREATE SEQUENCE privileges.', 'Flyway migration to V502 and the Oracle integration test passed.'],
    ['T-04', 'Initial Oracle privilege script included CREATE INDEX, which is not a grantable system privilege in this setup.', 'Removed invalid privilege from the grant statement; used only needed valid privileges.', 'Oracle test user setup completed successfully.'],
]
story += [table(issues_rows, [15*mm, 42*mm, 61*mm, 47*mm], font=7.1)]
story += [P('Known non-blocking observation', 'H2x'),
          P('Flyway emitted a compatibility warning because the local Oracle version is newer than the latest explicitly supported version in the bundled Flyway release. It did not prevent migrations or tests; V502 and all integration checks completed successfully. This should be reviewed during dependency maintenance, but it is not a current backend test failure.', 'Bodyx')]
story += [P('6. Conclusion and handover', 'H1x'),
          P('The M1 backend has passed its defined manual and automated test scope. Security behavior, role authorization, KYC state controls, conflict handling, data validation, and Oracle persistence were all demonstrated. The backend is ready for the next project phase, with the FLUXPAY_TEST schema retained for repeatable integration testing.', 'Bodyx'),
          Spacer(1, 4*mm), badge('BACKEND TEST PHASE COMPLETE', TEAL)]

doc = SimpleDocTemplate(str(OUT), pagesize=A4, rightMargin=18*mm, leftMargin=18*mm,
                        topMargin=20*mm, bottomMargin=19*mm, title='FluxPay M1 Backend Testing Report',
                        author='FluxPay Project Team')
doc.build(story, onFirstPage=header_footer, onLaterPages=header_footer)
print(OUT.resolve())
