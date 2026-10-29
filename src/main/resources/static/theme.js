// SPDX-License-Identifier: GPL-3.0-or-later
// Applies the light/dark choice saved by the top-bar switch before the page is drawn (loaded without defer;
// no inline scripts are allowed by the CSP). No saved choice means following the system.
try {
  const t = localStorage.getItem("theme");
  if (t === "light" || t === "dark") document.documentElement.setAttribute("data-theme", t);
} catch (e) {}
