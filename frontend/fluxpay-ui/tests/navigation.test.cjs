const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ko = require('knockout');
const index = fs.readFileSync(path.join(__dirname, '../src/index.html'), 'utf8');
const css = fs.readFileSync(path.join(__dirname, '../src/css/app.css'), 'utf8');

test('marketing links are hidden on home and workspace, retained on auth pages', () => {
  const binding = index.match(/<nav class="marketing-nav"[^>]+data-bind="([^"]+)"/)[1];
  for (const [home,publicPage,expected] of [[true,true,false],[false,false,false],[false,true,true]]) {
    assert.equal(vm.runInNewContext('({' + binding + '}).visible', {isHome:()=>home,isPublic:()=>publicPage}), expected);
  }
});
test('hamburger controls the same menu and exposes its expanded state', () => {
  assert.ok(index.includes('aria-controls="mobile-navigation"'));
  assert.ok(index.includes('id="mobile-navigation" class="mobile-nav"'));
  assert.ok(index.includes("'aria-expanded':menuOpen"));
  assert.ok(index.includes('data-bind="visible:menuOpen()&&!session.isAdmin()&&!isAdminWorkspace()"'));
});
test('mobile CSS reveals the menu when Knockout removes its inline display override', () => {
  const element={style:{display:''}};
  const open=ko.observable(false);
  ko.bindingHandlers.visible.update(element,open);
  assert.equal(element.style.display,'none');
  open(true);
  ko.bindingHandlers.visible.update(element,open);
  assert.equal(element.style.display,'');
  assert.match(css, /@media \(max-width: 900px\)\s*\{\s*\.mobile-nav\s*\{\s*display:\s*block;/);
  open(false);
  ko.bindingHandlers.visible.update(element,open);
  assert.equal(element.style.display,'none');
});
