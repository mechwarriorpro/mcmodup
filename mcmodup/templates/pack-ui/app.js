'use strict';
const $ = (id) => document.getElementById(id);
const selected = new Set();
let catalog, preview, ticket = 0, staging = false, completed = false;
const statusLabels = {
  available: 'Available', 'recipe-preview': 'Recipe preview', installed: 'In target', buildable: 'Source build', preview: 'Preview build',
  partial: 'Partial only', blocked: 'Blocked', auto: 'Auto add-on',
};
const selectable = row => ['available', 'buildable'].includes(row.status) ||
  (catalog?.output_pack && row.status === 'installed');
function element(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}
async function request(path, selection) {
  const options = selection === undefined ? {} : {
    method: 'POST', headers: { 'Content-Type': 'application/json', 'X-Mcmodup-Token': catalog.token },
    body: JSON.stringify({ selected: [...selection] }),
  };
  const response = await fetch(path, options);
  const data = await response.json();
  if (!response.ok) {
    const error = new Error(data.error || 'The workbench could not complete the request.');
    error.plan = data.plan;
    throw error;
  }
  return data;
}
function notice(text, error = false) {
  $('notice').hidden = !text;
  $('notice').textContent = text;
  $('notice').classList.toggle('error', error);
}
function list(focusId) {
  if (!catalog) return;
  const query = $('search').value.trim().toLowerCase();
  const addons = preview?.auto_addons || {};
  const rows = catalog.rows.filter((item) => `${item.name} ${item.id} ${item.mods.join(' ')}`.toLowerCase().includes(query));
  $('total').textContent = catalog.rows.length;
  $('checked').textContent = `${selected.size} selected`;
  const container = $('mod-list');
  container.replaceChildren();
  if (!rows.length) container.append(element('p', 'empty', query ? 'No mods match that search.' : 'No legacy archives found in mods/ or coremods/.'));
  for (const row of rows) {
    const auto = addons[row.id];
    const chosen = selected.has(row.id) || Boolean(auto);
    const label = element('label', 'mod-row' + (chosen ? ' is-selected' : ''));
    const check = element('input');
    check.type = 'checkbox';
    check.checked = chosen;
    check.disabled = Boolean(auto) || staging ||
      (row.status === 'recipe-preview' && !(catalog.allow_recipe_preview && catalog.output_pack));
    check.setAttribute('aria-label', `${row.name} (${row.id})`);
    check.addEventListener('change', () => {
      if (check.checked) selected.add(row.id); else selected.delete(row.id);
      refresh(row.id);
    });
    const info = element('span');
    info.append(element('span', 'mod-name', row.name),
      element('span', 'mod-file mono', `${row.id}${row.minecraft ? ` · MC ${row.minecraft}` : ''}`),
      element('span', 'mod-note', auto ? `Included: ${auto.reason}. ${row.reason}` : row.reason));
    const badge = auto ? 'auto' : row.status;
    label.append(check, info, element('span', `tag ${badge}`, statusLabels[badge]));
    container.append(label);
    if (focusId === row.id && !check.disabled) check.focus({ preventScroll: true });
  }
}
function details(parent, title, items) {
  if (!items.length) return;
  parent.append(element('h3', 'detail-label', title));
  for (const item of items) {
    const div = element('div', 'detail-row');
    const name = element('strong', '', item.name);
    if (item.action) name.append(element('span', `tag ${item.action}`, item.action));
    div.append(name, element('span', '', item.detail));
    parent.append(div);
  }
}
function showPlan(loading = false) {
  const deps = preview?.files.filter((row) => row.action === 'dependency') || [];
  const addons = Object.entries(preview?.auto_addons || {});
  const targetIssues = preview?.problems ?? (catalog?.output_pack ? [] : catalog?.target_issues) ?? [];
  $('stat-selected').textContent = selected.size;
  $('stat-addons').textContent = addons.length;
  $('stat-deps').textContent = deps.length;
  $('clear').disabled = !selected.size || staging;
  $('select-available').disabled = staging || !catalog?.rows.some(row => selectable(row) && !selected.has(row.id));
  $('stage').disabled = loading || staging || completed || !catalog?.can_stage || !preview?.ready;
  const content = $('plan-content');
  content.replaceChildren();
  if (loading) {
    content.append(element('p', 'plan-empty', 'Checking recipe previews, native builds and dependencies…'));
    return;
  }
  if (targetIssues.length) {
    const block = element('div', 'blockers');
    block.append(element('strong', '', `${targetIssues.length} ${targetIssues.length === 1 ? 'blocker' : 'blockers'} to review`));
    const ul = element('ul');
    for (const issue of targetIssues) ul.append(element('li', '', issue));
    block.append(ul);
    content.append(block);
  }
  if (!selected.size) {
    content.append(element('p', 'plan-empty', 'Select a mod to see what will be added or replaced.'));
    return;
  }
  details(content, 'Legacy version splits', (preview?.legacy_version_conflicts || []).map(row => ({ name: row.id,
    detail: row.requirements.map(req => `${req.consumer} needs ${req.range}; ${req.provider?.archive ?? req.status}`).join(' · '),
  })));
  details(content, 'Related add-ons', addons.map(([name, info]) => ({ name, detail: `${info.reason} · ${info.required_by}` })));
  const files = preview?.files || [];
  const buildFiles = (preview?.build_order || []).map(id => files.find(row => row.from === `build/${id}`)).filter(Boolean);
  details(content, 'Source build order', buildFiles.map((row, i) => {
    const result = preview.native_builds?.[row.file.split('/').at(-1)];
    return { name: `${i + 1}. ${Object.keys(row.ids).join(', ')}`, action: row.source_build.status === 'preview' ? 'preview' : 'build',
      detail: result ? `${result.gametests.passed} server GameTests passed · ${result.legacy_assets.unmapped} unverified legacy asset paths` : row.from.slice(6) };
  }));
  for (const row of buildFiles.filter(row => row.source_build.limitations.length)) {
    const list = element('ul', 'limits');
    for (const limitation of row.source_build.limitations) list.append(element('li', '', limitation));
    content.append(list);
  }
  const recipeFiles = files.filter(row => row.action === 'convert');
  details(content, 'Recipe preview from original', recipeFiles.map(row => ({
    name: Object.keys(row.ids).join(', '), action: 'convert',
    detail: `${row.from.slice(8)} · ${Object.values(row.conversion.recipes).reduce((total, count) => total + count, 0)} recipes`,
  })));
  for (const row of recipeFiles) {
    const limits = element('ul', 'limits');
    for (const limitation of row.conversion.limitations) limits.append(element('li', '', limitation));
    content.append(limits);
  }
  if (catalog?.allow_recipe_preview && catalog.output_pack) {
    $('stage-title').textContent = recipeFiles.length ? 'Stage recipe preview' : 'Create new pack';
  }
  details(content, 'Local files to add', files.filter(row => !['keep', 'build', 'convert'].includes(row.action)).map(row => ({
    name: Object.keys(row.ids).join(', '), detail: row.from, action: row.action,
  })));
  details(content, 'Target files to replace', Object.entries(preview?.replaced_target_files || {}).map(([name, detail]) => ({ name, detail })));
  details(content, 'Shared native dependencies', (preview?.native_dependencies || [])
    .filter(row => row.status === 'shared_native_provider').map(row => ({ name: `${row.id} ${row.provider.version}`,
      detail: `${row.provider.archive} · ${row.requirements.filter(req => req.type === 'required').length} required declarations`,
    })));
  details(content, 'Isolated native libraries', files.flatMap(row => row.private_libraries.map(lib => ({
    name: `${lib.id} ${lib.version}`, detail: `${lib.consumer} · ${lib.namespace}`,
  }))));
  if (preview?.instance_runtime) content.append(element('p', 'plan-empty', preview.instance_runtime.layout === 'neoforge-server'
    ? 'The new pack includes the local NeoForge server runtime. Boot and gameplay still need verification.'
    : 'Point an installed 1.21.1 NeoForge launcher profile at the new game directory.'));
  const retained = preview?.files.filter(row => row.action === 'keep').length;
  if (retained) content.append(element('p', 'plan-empty', `${retained} existing native ${retained === 1 ? 'JAR stays' : 'JARs stay'} in the target mods folder.`));
  if (preview?.ready) content.append(element('p', 'plan-empty', buildFiles.length
    ? (completed ? 'Source build checks passed. Test the staged modpack in a new world.'
      : 'Ready to compile and run server GameTests. No files are staged if a build or check fails.')
    : 'Local metadata checks passed. Test the staged mods in a fresh world before using an existing save.'));
}
async function refresh(focusId) {
  const current = ++ticket;
  preview = undefined;
  if (!completed) notice(catalog.can_stage ? '' : 'Read-only workbench. You can try the checklist and review a plan; no mods will be written.');
  list(focusId);
  showPlan(selected.size > 0);
  if (!selected.size) return;
  try {
    const result = await request('/api/plan', selected);
    if (current !== ticket) return;
    preview = result;
    list(focusId);
    showPlan();
  } catch (error) {
    if (current !== ticket) return;
    showPlan();
    notice(error.message, true);
  }
}
$('search').addEventListener('input', () => list());
$('clear').addEventListener('click', () => { selected.clear(); refresh(); });
$('select-available').addEventListener('click', () => {
  for (const row of catalog.rows) if (selectable(row)) selected.add(row.id);
  refresh();
});
$('stage').addEventListener('click', async () => {
  if (staging || completed || !preview?.ready) return;
  staging = true;
  list();
  showPlan();
  $('stage-hint').textContent = catalog.source_build_mode
    ? 'Compiling source and running NeoForge GameTests. Keep this tab open; builds can take several minutes.'
    : catalog.output_pack ? 'Rechecking local files and writing the new pack…' : 'Rechecking local files and writing the new mods folder…';
  try {
    const result = await request('/api/stage', selected);
    preview = result.plan;
    completed = true;
    notice(preview.mode === 'automatic-recipe-preview'
      ? `Recipe preview created at ${result.output}. This is not a full-parity update. See INSTALL.txt.`
      : catalog.output_pack
      ? `New pack created at ${result.output}. See INSTALL.txt for launch instructions. Both input packs are unchanged.`
      : `Updated mods staged at ${result.output}. See INSTALL.txt for the safe folder swap. Both input packs are unchanged.`);
    $('stage-hint').textContent = 'Staging complete. Start a new workbench with a new output folder to stage again.';
  } catch (error) {
    if (error.plan) preview = error.plan;
    notice(error.message, true);
    $('stage-hint').textContent = 'Nothing was installed into your target pack.';
  } finally {
    staging = false;
    list();
    showPlan();
  }
});
(async () => {
  try {
    catalog = await request('/api/catalog');
    $('source').textContent = catalog.source;
    $('target').textContent = catalog.target;
    if (catalog.source_build_mode) {
      $('intro-copy').textContent = 'Build standalone NeoForge JARs from reviewed recipes for the legacy releases you select. Add-ons and dependencies are included in the plan.';
      $('stage-title').textContent = 'Build and stage mods';
      $('select-available').textContent = 'Select buildable';
      $('footer-copy').textContent = 'Pinned sources and local mod dependencies · No Legacy Connector';
    }
    if (catalog.output_pack) {
      if (!catalog.source_build_mode) $('intro-copy').textContent = 'Choose the legacy mods you want to update. Matching native builds, their dependencies and the target runtime go into a new pack folder.';
      $('stage-title').textContent = catalog.source_build_mode ? 'Build new pack' : 'Create new pack';
      $('stage-hint').textContent = 'Only the selected updates, related add-ons, required dependencies and target runtime files are copied.';
    }
    const sourceVersions = [...new Set(catalog.source_bin.minecraft.map(row => row.version))];
    $('source-detail').textContent = (sourceVersions.length ? `MC ${sourceVersions.join(', ')} · ` : '') + 'mods/ & coremods/';
    $('target-detail').textContent = `MC ${catalog.minecraft} · NeoForge ${catalog.neoforge}`;
    if (catalog.warnings.length) {
      $('scan-notes').hidden = false;
      $('scan-summary').textContent = `Scan notes (${catalog.warnings.length})`;
      for (const warning of catalog.warnings) $('scan-items').append(element('li', '', warning));
    }
    if (!catalog.can_stage) $('stage-hint').textContent = 'Preview only. Run with --output-pack NEW_DIR or -o NEW_DIR to write files.';
    refresh();
  } catch (error) {
    notice(error.message, true);
    $('mod-list').replaceChildren(element('p', 'empty', 'Could not read the mod catalog.'));
  }
})();
