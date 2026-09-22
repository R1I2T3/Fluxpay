const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs'),path=require('node:path');
const read=file=>fs.readFileSync(path.join(__dirname,'../src',file),'utf8');
test('shell keeps customer bottom tabs and guards admin sidebar to administrators',()=>{
  const html=read('index.html');
  assert.match(html,/← Back to home/);assert.match(html,/<header class="site-header">/);
  assert.match(html,/class="profile-menu"/);assert.match(html,/text:session.user\(\).fullName/);assert.match(html,/class="bottom-nav"/);
  assert.match(html,/foreach:bottomNav/);
  assert.match(html,/visible:!isPublic\(\)&&!isAdminWorkspace\(\)/);
  assert.match(html,/<!-- ko if:isAdminWorkspace -->[\s\S]*class="admin-sidebar"/);
  assert.match(html,/foreach:adminNavGroups/);
  assert.match(html,/'aria-current':\$root\.activeAdminPath\(\)===path\?'page':null/);
  assert.match(html,/text:environment\.label/);
  assert.match(html,/class="skip-link"/);
  assert.doesNotMatch(html,/Audit Log|Governance/);
  assert.match(read('css/mobile-workspace.css'),/@media\(min-width:768px\)\{\.bottom-nav\{display:none !important;/);
});
test('dashboard content exposes every customer destination without a menu',()=>{
  const html=read('ts/views/dashboard.html');
  for(const destination of ['wallets','payments-new','payments-list','recipients','account','kyc','tickets'])assert.ok(html.includes('data-route="'+destination+'"'),destination);
  assert.match(html,/click:paySomeoneNew/);assert.match(html,/click:\$parent.payPerson/);
});
test('Send offers an inline recipient form beside the existing dropdown',()=>{
  const html=read('ts/views/payments-new.html');
  assert.match(html,/click:addRecipient/);assert.match(html,/options:activeRecipients/);assert.match(html,/submit:saveRecipient/);assert.match(html,/Save &amp; use this recipient/);
  assert.doesNotMatch(html,/data-route="recipients"/);
  for(const field of ['amount','recipientName','otherBankName','account','country'])assert.ok(html.includes('textInput:'+field),'input event binding for '+field);
  assert.match(html,/select required data-bind="value:bankName"/);
});
