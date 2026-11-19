const theme = document.querySelector('.theme');
const systemTheme = window.matchMedia('(prefers-color-scheme: dark)');
const root = document.documentElement;
const key = 'ciallo-theme';

try {
  const saved = localStorage.getItem(key);
  if (saved === 'light' || saved === 'dark') root.dataset.theme = saved;
} catch {
  // A blocked storage area does not prevent switching themes in this tab.
}

function syncThemeControl() {
  const isDark = root.dataset.theme ? root.dataset.theme === 'dark' : systemTheme.matches;
  theme.setAttribute('aria-pressed', String(isDark));
}

theme.hidden = false;
syncThemeControl();
systemTheme.addEventListener('change', syncThemeControl);
theme.addEventListener('click', () => {
  root.dataset.theme = theme.getAttribute('aria-pressed') === 'true' ? 'light' : 'dark';
  syncThemeControl();
  try {
    localStorage.setItem(key, root.dataset.theme);
  } catch {
    // Keep the selected theme active for the current page.
  }
});
