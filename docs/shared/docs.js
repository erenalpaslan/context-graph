/* Shared docs layout builder.
   Each docs page sets: const PAGE = 'page-id'; const BASE = '../../';
   then includes this script at the end of <body>.
*/
(function () {
  const NAV = [
    {
      title: 'Getting Started',
      items: [
        { id: 'introduction', label: 'Introduction',    path: 'getting-started/introduction/index.html' },
        { id: 'quickstart',   label: 'Quick Start',     path: 'getting-started/quickstart/index.html' },
        { id: 'configuration',label: 'Configuration',   path: 'getting-started/configuration/index.html' },
      ]
    },
    {
      title: 'Core Concepts',
      items: [
        { id: 'how-it-works', label: 'How It Works', path: 'core-concepts/how-it-works/index.html' },
      ]
    },
    {
      title: 'Reference',
      items: [
        { id: 'mcp-server', label: 'MCP Server', path: 'reference/mcp-server/index.html' },
        { id: 'cli',        label: 'CLI',         path: 'reference/cli/index.html' },
      ]
    }
  ];

  // Flatten for prev/next
  const ALL = NAV.flatMap(s => s.items);
  const idx = ALL.findIndex(i => i.id === PAGE);
  const prev = idx > 0 ? ALL[idx - 1] : null;
  const next = idx < ALL.length - 1 ? ALL[idx + 1] : null;

  function icon() {
    return `<svg width="24" height="24" viewBox="0 0 28 28" fill="none">
      <circle cx="14" cy="14" r="4" fill="#7c6aff"/>
      <circle cx="6" cy="7" r="2.5" fill="#a78bfa"/>
      <circle cx="22" cy="7" r="2.5" fill="#a78bfa"/>
      <circle cx="6" cy="21" r="2.5" fill="#a78bfa"/>
      <circle cx="22" cy="21" r="2.5" fill="#a78bfa"/>
      <line x1="10" y1="12" x2="7.5" y2="9" stroke="#7c6aff" stroke-width="1.5"/>
      <line x1="18" y1="12" x2="20.5" y2="9" stroke="#7c6aff" stroke-width="1.5"/>
      <line x1="10" y1="16" x2="7.5" y2="19" stroke="#7c6aff" stroke-width="1.5"/>
      <line x1="18" y1="16" x2="20.5" y2="19" stroke="#7c6aff" stroke-width="1.5"/>
    </svg>`;
  }

  function ghIcon() {
    return `<svg width="15" height="15" viewBox="0 0 16 16" fill="currentColor"><path d="M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27.68 0 1.36.09 2 .27 1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.013 8.013 0 0016 8c0-4.42-3.58-8-8-8z"/></svg>`;
  }

  const sidebarHtml = NAV.map(section => `
    <div class="sidebar-section">
      <div class="sidebar-section-title">${section.title}</div>
      <ul class="sidebar-links">
        ${section.items.map(item => `
          <li><a href="${BASE}${item.path}" class="${item.id === PAGE ? 'active' : ''}">${item.label}</a></li>
        `).join('')}
      </ul>
    </div>
  `).join('');

  const navFooter = `
    <div class="docs-nav-footer">
      ${prev ? `<a class="docs-nav-link" href="${BASE}${prev.path}">
        <span class="docs-nav-link-label">← Previous</span>
        <span class="docs-nav-link-title">${prev.label}</span>
      </a>` : '<span></span>'}
      ${next ? `<a class="docs-nav-link next" href="${BASE}${next.path}">
        <span class="docs-nav-link-label">Next →</span>
        <span class="docs-nav-link-title">${next.label}</span>
      </a>` : ''}
    </div>
  `;

  const article = document.querySelector('article');
  const content = article ? article.innerHTML : '';
  const title = document.title;

  document.body.innerHTML = `
    <nav class="site-nav">
      <a class="nav-logo" href="${BASE}index.html">${icon()} ContextGraph</a>
      <ul class="nav-right">
        <li><a href="${BASE}getting-started/introduction/index.html">Docs</a></li>
        <li><a href="${BASE}reference/mcp-server/index.html">Reference</a></li>
        <li><a class="nav-gh" href="https://github.com/erenalpaslan/context-graph" target="_blank">${ghIcon()} GitHub</a></li>
      </ul>
    </nav>
    <div class="docs-layout">
      <aside class="docs-sidebar">${sidebarHtml}</aside>
      <main class="docs-main">
        ${content}
        ${navFooter}
      </main>
    </div>
  `;
})();
