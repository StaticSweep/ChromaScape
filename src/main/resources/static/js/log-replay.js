// Log Replay Page JavaScript

const API_BASE = '/api/logs';
let currentPage = 0;
const pageSize = 50;
let currentLogs = [];
let allLoaded = false;

// DOM Elements
const filterScript = document.getElementById('filterScript');
const filterLevel = document.getElementById('filterLevel');
const filterStart = document.getElementById('filterStart');
const filterEnd = document.getElementById('filterEnd');
const filterSearch = document.getElementById('filterSearch');
const logsTableBody = document.getElementById('logsTableBody');
const pagination = document.getElementById('pagination');
const timelineContainer = document.getElementById('timelineContainer');

// Stats elements
const statTotal = document.getElementById('statTotal');
const statInfo = document.getElementById('statInfo');
const statWarn = document.getElementById('statWarn');
const statError = document.getElementById('statError');
const statDebug = document.getElementById('statDebug');
const statScripts = document.getElementById('statScripts');

// Initialize page
document.addEventListener('DOMContentLoaded', () => {
  loadScripts();
  loadStats();
  loadLogs();
  loadScriptEvents();
});

// Load scripts for filter dropdown
async function loadScripts() {
  try {
    const response = await fetch(`${API_BASE}/scripts`);
    const scripts = await response.json();

    filterScript.innerHTML = '<option value="">All Scripts</option>';
    scripts.forEach(script => {
      const option = document.createElement('option');
      option.value = script;
      option.textContent = script;
      filterScript.appendChild(option);
    });
  } catch (error) {
    console.error('Failed to load scripts:', error);
  }
}

// Load statistics
async function loadStats() {
  try {
    const response = await fetch(`${API_BASE}/stats`);
    const stats = await response.json();

    statTotal.textContent = stats.totalLogs || 0;
    statInfo.textContent = stats.infoLogs || 0;
    statWarn.textContent = stats.warnLogs || 0;
    statError.textContent = stats.errorLogs || 0;
    statDebug.textContent = stats.debugLogs || 0;
    statScripts.textContent = stats.uniqueScripts || 0;
  } catch (error) {
    console.error('Failed to load stats:', error);
  }
}

// Load logs with current filters
async function loadLogs(reset = true) {
  if (reset) {
    currentPage = 0;
    currentLogs = [];
    allLoaded = false;
  }

  if (allLoaded) {
    return;
  }

  try {
    const params = new URLSearchParams();
    const script = filterScript.value;
    const level = filterLevel.value;
    const start = filterStart.value;
    const end = filterEnd.value;
    const search = filterSearch.value;

    if (script) params.append('script', script);
    if (level) params.append('level', level);
    if (start) params.append('start', new Date(start).toISOString());
    if (end) params.append('end', new Date(end).toISOString());
    if (search) params.append('search', search);

    const response = await fetch(`${API_BASE}/history?${params.toString()}`);
    const logs = await response.json();

    if (logs.length === 0) {
      allLoaded = true;
      if (currentLogs.length === 0) {
        logsTableBody.innerHTML =
          '<tr><td colspan="5" class="text-center">No logs found matching your filters</td></tr>';
      }
      return;
    }

    currentLogs = currentLogs.concat(logs);
    displayLogs();

  } catch (error) {
    console.error('Failed to load logs:', error);
    logsTableBody.innerHTML =
      '<tr><td colspan="5" class="text-center text-danger">Failed to load logs</td></tr>';
  }
}

// Display logs in table
function displayLogs() {
  const start = currentPage * pageSize;
  const end = start + pageSize;
  const pageLogs = currentLogs.slice(start, end);

  if (pageLogs.length === 0 && currentLogs.length > 0) {
    currentPage = Math.max(0, Math.ceil(currentLogs.length / pageSize) - 1);
    displayLogs();
    return;
  }

  logsTableBody.innerHTML = pageLogs.map(log => {
    const timestamp = new Date(log.timestamp).toLocaleString();
    const levelClass = `log-level-${log.level}`;
    return `
      <tr>
        <td><small>${timestamp}</small></td>
        <td><span class="${levelClass}">${log.level}</span></td>
        <td>${log.scriptName || '-'}</td>
        <td>${log.source || '-'}</td>
        <td><div class="log-message" title="${escapeHtml(log.message)}">${escapeHtml(log.message)}</div></td>
      </tr>
    `;
  }).join('');

  updatePagination();
}

// Update pagination controls
function updatePagination() {
  const totalPages = Math.ceil(currentLogs.length / pageSize);
  pagination.innerHTML = '';

  if (totalPages <= 1) {
    return;
  }

  // Previous button
  const prevLi = document.createElement('li');
  prevLi.className = `page-item ${currentPage === 0 ? 'disabled' : ''}`;
  prevLi.innerHTML = '<a class="page-link" href="#" onclick="changePage(-1); return false;">Previous</a>';
  pagination.appendChild(prevLi);

  // Page numbers
  for (let i = 0; i < totalPages; i++) {
    const li = document.createElement('li');
    li.className = `page-item ${i === currentPage ? 'active' : ''}`;
    li.innerHTML = `<a class="page-link" href="#" onclick="goToPage(${i}); return false;">${i + 1}</a>`;
    pagination.appendChild(li);
  }

  // Next button
  const nextLi = document.createElement('li');
  nextLi.className = `page-item ${currentPage >= totalPages - 1 ? 'disabled' : ''}`;
  nextLi.innerHTML =
    '<a class="page-link" href="#" onclick="changePage(1); return false;">Next</a>';
  pagination.appendChild(nextLi);
}

// Change page
function changePage(delta) {
  const totalPages = Math.ceil(currentLogs.length / pageSize);
  const newPage = currentPage + delta;
  if (newPage >= 0 && newPage < totalPages) {
    currentPage = newPage;
    displayLogs();
  }
}

// Go to specific page
function goToPage(page) {
  currentPage = page;
  displayLogs();
}

// Load script events timeline
async function loadScriptEvents() {
  try {
    const response = await fetch(`${API_BASE}/events`);
    const events = await response.json();

    if (events.length === 0) {
      timelineContainer.innerHTML = '<div class="text-center">No script events recorded</div>';
      return;
    }

    timelineContainer.innerHTML = '<div class="timeline-container">' +
      events.map(event => {
        const timestamp = new Date(event.timestamp).toLocaleString();
        const typeClass = event.eventType.toLowerCase();
        return `
          <div class="timeline-event ${typeClass}">
            <div class="timeline-time">${timestamp}</div>
            <div class="timeline-type ${typeClass}">${event.eventType}</div>
            <div class="timeline-script">${escapeHtml(event.scriptName)}</div>
          </div>
        `;
      }).join('') +
      '</div>';
  } catch (error) {
    console.error('Failed to load script events:', error);
    timelineContainer.innerHTML = '<div class="text-center text-danger">Failed to load events</div>';
  }
}

// Apply filters
function applyFilters() {
  loadLogs(true);
  loadStats();
}

// Clear filters
function clearFilters() {
  filterScript.value = '';
  filterLevel.value = '';
  filterStart.value = '';
  filterEnd.value = '';
  filterSearch.value = '';
  applyFilters();
}

// Export logs as JSON
function exportLogs() {
  if (currentLogs.length === 0) {
    alert('No logs to export');
    return;
  }

  const dataStr = JSON.stringify(currentLogs, null, 2);
  const dataBlob = new Blob([dataStr], { type: 'application/json' });
  const url = URL.createObjectURL(dataBlob);
  const link = document.createElement('a');
  link.href = url;
  link.download = `chromascape-logs-${new Date().toISOString()}.json`;
  link.click();
  URL.revokeObjectURL(url);
}

// Escape HTML to prevent XSS
function escapeHtml(text) {
  const div = document.createElement('div');
  div.textContent = text;
  return div.innerHTML;
}

// Auto-refresh stats every 30 seconds
setInterval(loadStats, 30000);
