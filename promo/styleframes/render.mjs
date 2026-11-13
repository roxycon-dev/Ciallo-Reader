import { chromium } from 'playwright-core';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { dirname, join } from 'node:path';
import { mkdir } from 'node:fs/promises';

const currentDir = dirname(fileURLToPath(import.meta.url));
const outDir = join(currentDir, 'renders');
await mkdir(outDir, { recursive: true });

const executablePath = process.env.CIALLO_CHROME_PATH || 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';
const browser = await chromium.launch({ executablePath, headless: true });
try {
  const page = await browser.newPage({ viewport: { width: 1920, height: 1080 }, deviceScaleFactor: 1 });
  await page.goto(pathToFileURL(join(currentDir, 'index.html')).href);
  await page.evaluate(() => document.fonts.ready);
  for (const name of ['hero', 'glass', 'roxy']) {
    const target = page.locator(`#${name}`);
    await target.screenshot({ path: join(outDir, `${name}.png`) });
    console.log(join(outDir, `${name}.png`));
  }
} finally {
  await browser.close();
}

