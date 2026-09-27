// tests/helpers/load-typescript.cjs
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');

const sourceRoot = path.join(__dirname, '../../src');

function compile(relativePath) {
  return ts.transpileModule(fs.readFileSync(path.join(sourceRoot, relativePath), 'utf8'), {
    compilerOptions: {module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020}
  }).outputText;
}

function load(relativePath, dependencies = {}, globals = {}) {
  const module={exports:{}};
  const context = {
    module,
    exports: module.exports,
    require(name) {
      if (Object.prototype.hasOwnProperty.call(dependencies, name)) return dependencies[name];
      throw new Error(`Missing test dependency: ${name}`);
    },
    ...globals
  };
  vm.runInNewContext(compile(relativePath), context);
  return context.module.exports;
}

module.exports = {compile, load};
