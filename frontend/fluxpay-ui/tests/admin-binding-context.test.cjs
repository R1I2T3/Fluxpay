const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ko = require('knockout/build/output/knockout-latest.debug');

const read = (file) => fs.readFileSync(path.join(__dirname, '../src', file), 'utf8');

function bindings(html) {
  return [
    ...Array.from(html.matchAll(/data-bind="([^"]+)"/g), (match) => match[1]),
    ...Array.from(html.matchAll(/<!--\s*ko\s+(.+?)\s*-->/g), (match) => match[1]),
  ];
}

function bindingContaining(html, token) {
  const binding = bindings(html).find((candidate) => candidate.includes(token));
  assert.ok(binding, `missing binding containing ${token}`);
  return binding;
}

function openingTagForClass(html, className) {
  const escaped = className.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const match = html.match(new RegExp(`<[^>]+class="[^"]*\\b${escaped}\\b[^"]*"[^>]*>`, 'i'));
  assert.ok(match, `missing .${className}`);
  return match[0];
}

function accessors(binding, context) {
  return ko.bindingProvider.instance.parseBindingsString(
    binding,
    context,
    {},
    { valueAccessors: true },
  );
}

function moduleContext(viewModel) {
  return new ko.bindingContext(null).createChildContext(viewModel);
}

test('payout route bindings work when Oracle JET supplies a null outer root', () => {
  const editRoute = () => {};
  const workspace = {
    routeProtected: (route) => route.systemProtected === true,
    providerDisplayName: (id) => (id === 'provider-1' ? 'Wise' : 'Unknown'),
    editRoute,
    busy: ko.observable(false),
    confirmRouteSave: () => {},
  };
  const viewModel = { workspace, environment: { label: 'Sandbox' } };
  const route = { providerId: 'provider-1', systemProtected: true };
  const html = read('ts/views/admin-routes.html');
  const routeContext = moduleContext(viewModel).createChildContext(route);

  const protectedBinding = accessors(bindingContaining(html, 'routeProtected'), routeContext);
  const providerBinding = accessors(bindingContaining(html, 'providerDisplayName'), routeContext);
  const editBinding = accessors(bindingContaining(html, 'editRoute'), routeContext);
  assert.equal(ko.unwrap(protectedBinding.if()), true);
  assert.equal(ko.unwrap(providerBinding.text()), 'Wise');
  assert.equal(editBinding.click(), editRoute);

  const workspaceContext = moduleContext(viewModel).createChildContext(workspace);
  const targetBindings = bindings(html).filter((binding) =>
    binding.includes("Target environment: '+"),
  );
  assert.equal(targetBindings.length, 3);
  const formTargetBinding = accessors(targetBindings[1], workspaceContext);
  const reviewTargetBinding = accessors(targetBindings[2], workspaceContext);
  const applyBinding = accessors(bindingContaining(html, "Apply in '+"), workspaceContext);
  assert.equal(ko.unwrap(formTargetBinding.text()), 'Target environment: Sandbox');
  assert.equal(ko.unwrap(reviewTargetBinding.text()), 'Target environment: Sandbox');
  assert.equal(ko.unwrap(applyBinding.text()), 'Apply in Sandbox');
});

test('review workspaces hide detail cards until a record is selected', () => {
  const cases = [
    ['ts/views/admin-kyc.html', 'review'],
    ['ts/views/admin-compliance.html', 'selectedCase'],
    ['ts/views/admin-tickets.html', 'selectedTicket'],
  ];

  for (const [file, property] of cases) {
    const html = read(file);
    const selection = ko.observable();
    const context = moduleContext({ [property]: selection });
    const workspaceBinding = openingTagForClass(html, 'review-workspace').match(
      /data-bind="([^"]+)"/,
    );
    const detailBinding = openingTagForClass(html, 'record-detail').match(/data-bind="([^"]+)"/);
    const evidenceBinding = openingTagForClass(html, 'evidence-panel').match(/data-bind="([^"]+)"/);
    assert.ok(workspaceBinding, `${file} workspace selection binding`);
    assert.ok(detailBinding, `${file} detail selection binding`);
    assert.ok(evidenceBinding, `${file} evidence selection binding`);

    const workspaceAccessors = accessors(workspaceBinding[1], context);
    const detailAccessors = accessors(detailBinding[1], context);
    const evidenceAccessors = accessors(evidenceBinding[1], context);
    assert.equal(ko.unwrap(detailAccessors.visible()), false, `${file} detail hidden`);
    assert.equal(ko.unwrap(evidenceAccessors.visible()), false, `${file} evidence hidden`);
    assert.equal(workspaceAccessors.css()['has-selection'], false, `${file} compact queue`);

    selection({ id: 'selected' });
    assert.equal(ko.unwrap(detailAccessors.visible()), true, `${file} detail visible`);
    assert.equal(ko.unwrap(evidenceAccessors.visible()), true, `${file} evidence visible`);
    assert.equal(workspaceAccessors.css()['has-selection'], true, `${file} full workspace`);
  }
});
