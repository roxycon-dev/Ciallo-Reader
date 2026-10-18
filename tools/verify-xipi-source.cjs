const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '..');
const assets = path.join(root, 'app/src/androidTest/assets/xipi');
const data = JSON.parse(fs.readFileSync(path.join(assets, 'chapter-180-5.json'), 'utf8'));
const expected = JSON.parse(fs.readFileSync(path.join(assets, 'expected-urls.json'), 'utf8'));
const context = vm.createContext({
  ComicSource: class {}, Comic: class { constructor(v) { Object.assign(this, v); } },
  Convert: {
    encodeUtf8: s => Uint8Array.from(Buffer.from(s)), decodeUtf8: a => Buffer.from(a).toString('utf8'),
    encodeBase64: a => Buffer.from(a).toString('base64'), decodeBase64: s => Uint8Array.from(Buffer.from(s, 'base64')),
  },
});
vm.runInContext(fs.readFileSync(path.join(root, 'app/src/main/assets/js_extra/xipi.js'), 'utf8') + '\nglobalThis.src = new Xipi();', context);
const src = context.src;
assert.deepEqual(JSON.parse(JSON.stringify(src.decodeImages(data))), expected);
assert.equal(src.mid('bTozMDg0MA-any-slug'), 'bTozMDg0MA');
assert.equal(src.chapterIdentity('bTozMDg0MC1jOjE5MjkzOA-MzA4NDA6MjczLjAw').apiHid, 'YzoxOTI5Mzg-MzA4NDA6MjczLjAw');
assert.throws(() => src.decodeImages({...data, images:'invalid'}));
assert.throws(() => src.decodeImages({...data, sid:1e22}));
assert.throws(() => src.decodeImages({...data, order_id:0.5}));
assert.throws(() => src.mid('invalid'));
assert.throws(() => src.chapterIdentity('invalid'));
console.log('PASS Xipi image codec matches archived public reader payload (2 pages, decoy excluded)');
console.log('PASS Xipi IDs and malformed image metadata fail closed');
