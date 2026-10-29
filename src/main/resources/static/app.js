// SPDX-License-Identifier: GPL-3.0-or-later
// Server-sent "update" events make every live block re-fetch its fragment (see Pages.kt).
// No inline scripts are allowed by the CSP, so buttons use data attributes handled here.
(function () {
  const banner = document.getElementById("connection");
  const csrf = document.querySelector('meta[name="csrf-token"]').content;
  // Every POST must carry the session's CSRF token (checked server-side).
  document.body.addEventListener("htmx:configRequest", function (e) {
    e.detail.headers["X-CSRF-Token"] = csrf;
    const tab = e.detail.parameters["ui.tab"];
    if (tab) sentTab = tab;
  });
  const es = new EventSource("/events");
  es.addEventListener("update", function () {
    htmx.trigger(document.body, "bt-update");
  });
  es.onopen = function () {
    banner.classList.remove("shown");
    htmx.trigger(document.body, "bt-update");
  };
  es.onerror = function () {
    banner.classList.add("shown");
  };

  // Sliders show their value next to them while dragging.
  document.addEventListener("input", function (e) {
    const id = e.target.getAttribute && e.target.getAttribute("data-output");
    if (id) {
      const out = document.getElementById(id);
      if (out) out.textContent = e.target.value;
    }
  });

  // Tabbed pages (Settings, About). Switching only changes which panel is shown. The server renders the tab from
  // ?tab=, or on Settings the one posted as ui.tab, so a form swap keeps it (unless a failed save moved to a tab
  // with a problem). An old #section link opens that tab.
  const narrow = window.matchMedia("(max-width: 900px)");
  let chosenTab = null;
  let sentTab = null;
  let hashTab = location.hash.slice(1);
  function pageTabs() {
    return Array.from(document.querySelectorAll(".tabbed [role=tab]"));
  }
  // Centre the tab in the horizontal strip (not the page); the cut-off neighbours show there are more.
  function revealTab(tab) {
    const strip = tab.parentElement.getBoundingClientRect();
    const r = tab.getBoundingClientRect();
    tab.parentElement.scrollLeft += r.left + r.width / 2 - (strip.left + strip.width / 2);
  }
  function showTab(tab, focus) {
    pageTabs().forEach(function (t) {
      const on = t === tab;
      t.setAttribute("aria-selected", on ? "true" : "false");
      t.tabIndex = on ? 0 : -1;
      const panel = document.getElementById(t.getAttribute("aria-controls"));
      if (panel) panel.hidden = !on;
    });
    chosenTab = tab.getAttribute("data-tab");
    const input = document.getElementById("ui-tab");
    if (input) input.value = chosenTab;
    // Keep the tab in the address so a reload stays on it (a plain address already means the first one).
    const url = new URL(location.href);
    const inUrl = url.searchParams.get("tab");
    if (inUrl !== chosenTab && (inUrl !== null || tab !== pageTabs()[0])) {
      url.searchParams.set("tab", chosenTab);
      url.hash = "";
      history.replaceState(null, "", url);
    }
    if (focus) tab.focus();
    revealTab(tab);
  }
  htmx.onLoad(function (elt) {
    const list = elt.querySelector && elt.querySelector(".tabbed [role=tablist]");
    if (!list) return;
    list.setAttribute("aria-orientation", narrow.matches ? "horizontal" : "vertical");
    const tabs = pageTabs();
    const shown = tabs.find(function (t) { return t.getAttribute("aria-selected") === "true"; });
    const clicked = tabs.find(function (t) { return t.getAttribute("data-tab") === chosenTab; });
    const hashed = tabs.find(function (t) { return t.getAttribute("data-tab") === hashTab; });
    hashTab = "";
    if (hashed) showTab(hashed, false);
    // A tab clicked while a request was in flight wins over the one that request carried.
    else if (clicked && shown && shown !== clicked && shown.getAttribute("data-tab") === sentTab) showTab(clicked, false);
    else if (shown) showTab(shown, false);
  });
  narrow.addEventListener("change", function () {
    const list = document.querySelector(".tabbed [role=tablist]");
    if (list) list.setAttribute("aria-orientation", narrow.matches ? "horizontal" : "vertical");
  });
  document.addEventListener("keydown", function (e) {
    const tab = e.target.closest && e.target.closest(".tabbed [role=tab]");
    if (!tab) return;
    const tabs = pageTabs();
    const i = tabs.indexOf(tab);
    const next = {
      ArrowDown: tabs[(i + 1) % tabs.length],
      ArrowRight: tabs[(i + 1) % tabs.length],
      ArrowUp: tabs[(i - 1 + tabs.length) % tabs.length],
      ArrowLeft: tabs[(i - 1 + tabs.length) % tabs.length],
      Home: tabs[0],
      End: tabs[tabs.length - 1],
    }[e.key];
    if (!next) return;
    e.preventDefault();
    showTab(next, true);
  });

  // Light/dark switch. The choice is kept in this browser (theme.js applies it on load); picking what the
  // system already uses goes back to following the system.
  const systemDark = window.matchMedia("(prefers-color-scheme: dark)");
  function isDark() {
    const t = document.documentElement.getAttribute("data-theme");
    return t ? t === "dark" : systemDark.matches;
  }
  function syncThemeToggle() {
    const b = document.getElementById("theme-toggle");
    if (b) b.setAttribute("aria-pressed", isDark() ? "true" : "false");
  }
  syncThemeToggle();
  systemDark.addEventListener("change", syncThemeToggle);
  function toggleTheme() {
    const dark = !isDark();
    const root = document.documentElement;
    const follow = dark === systemDark.matches;
    if (follow) root.removeAttribute("data-theme");
    else root.setAttribute("data-theme", dark ? "dark" : "light");
    try {
      if (follow) localStorage.removeItem("theme");
      else localStorage.setItem("theme", dark ? "dark" : "light");
    } catch (err) {}
    syncThemeToggle();
  }

  document.addEventListener("click", function (e) {
    if (e.target.closest("#theme-toggle")) {
      toggleTheme();
      return;
    }
    const tab = e.target.closest(".tabbed [role=tab]");
    if (tab) {
      showTab(tab, false);
      // Start the new section at its top if the page was scrolled down into the previous one.
      const box = tab.closest(".tabbed");
      if (box.getBoundingClientRect().top < 0) box.scrollIntoView();
      return;
    }
    const add = e.target.closest("[data-add-root]");
    if (add) {
      const box = document.getElementById("sources-roots");
      if (!box) return;
      const path = add.getAttribute("data-add-root");
      const lines = box.value.split("\n").map(function (l) { return l.trim(); }).filter(Boolean);
      if (lines.indexOf(path) === -1) lines.push(path);
      box.value = lines.join("\n");
      box.dispatchEvent(new Event("change", { bubbles: true }));
      return;
    }
    const gen = e.target.closest("[data-generate-token]");
    if (gen) {
      const input = document.getElementById(gen.getAttribute("data-generate-token"));
      const bytes = new Uint8Array(24);
      crypto.getRandomValues(bytes);
      input.value = Array.from(bytes, function (b) { return b.toString(16).padStart(2, "0"); }).join("");
      input.type = "text";
      input.dispatchEvent(new Event("change", { bubbles: true }));
    }
  });
})();
