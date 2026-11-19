(() => {
  "use strict";

  const SVG_NS = "http://www.w3.org/2000/svg";

  const MODULE_META = {
    arch: {
      title: "Arquitetura do Sistema",
      subtitle: "Fluxo distribuído RMI/TCP, motores e divisão dos 5 membros"
    },
    studio: {
      title: "Estúdio Visual",
      subtitle: "Upload, geração sintética, cortina Antes/Depois e Mapa de Diff"
    },
    benchmark: {
      title: "Benchmarks Comparativos",
      subtitle: "Comparação dos 5 motores, Speedup real, Eficiência e exportação"
    },
    concurrency: {
      title: "Concorrência & Semáforo",
      subtitle: "Disparo simultâneo de clientes RMI/TCP e linha do tempo Gantt"
    }
  };

  const MEMBER_SCRIPTS = {
    1: {
      badge: "Membro 1",
      title: "Rede RMI & Protocolo TCP",
      focus: "Separação entre plano de controle leve (Java RMI) e transporte binário em streaming de imagens (TCP 64 KB).",
      points: [
        "O cliente localiza o coordenador no RMI Registry, testa ping() e submete JobRequest recebendo um JobTicket.",
        "As imagens PNG e os relatórios CSV/TXT trafegam por sockets TCP dedicados com buffer de 64 KB.",
        "Frames tipados (UPLOAD, PROGRESS, REPORT, IMAGE, DONE, ERROR) garantem feedback em tempo real sem sobrecarga no RMI."
      ],
      classes: "CoordinatorRmiService · TcpProtocol · GuiClientService",
      demoModule: "studio"
    },
    2: {
      badge: "Membro 2",
      title: "Coordenador & Semáforo Concorrente",
      focus: "Controle de admissão com Semaphore(maxConcurrent) para impedir saturação de CPU, memória e disco sob múltiplas requisições.",
      points: [
        "Cada cliente paralelo recebe um JobTicket imediato com posição na fila antes de abrir o canal TCP.",
        "O Semaphore limita quantos jobs pesados rodam simultaneamente no servidor embarcado ou remoto.",
        "Ao concluir, o release() no bloco finally libera o próximo cliente aguardando na fila."
      ],
      classes: "CoordinatorMain · CoordinatorServerImpl · Semaphore",
      demoModule: "concurrency"
    },
    3: {
      badge: "Membro 3",
      title: "Paralelismo CPU (Rows & Blocks)",
      focus: "Decomposição de convoluções 3×3 e 5×5 em múltiplas threads Java por faixas horizontais ou blocos 2D (tiles).",
      points: [
        "Threads Rows divide a altura da imagem entre workers sem contenção de escrita no buffer de saída.",
        "Threads Blocks divide a matriz em tiles configuráveis (ex.: 64×64 px) para melhor localidade de cache L1/L2.",
        "Ambos preservam bordas por clamp idêntico ao motor Sequencial, garantindo zero divergência no PixelVerifier."
      ],
      classes: "SequentialEngine · ThreadedRowsEngine · ThreadedBlocksEngine",
      demoModule: "studio"
    },
    4: {
      badge: "Membro 4",
      title: "Distribuído — MPJ Express",
      focus: "Processamento distribuído via troca de mensagens MPI (Scatter/Gather de faixas com borda halo).",
      points: [
        "O Rank 0 carrega a imagem e distribui fatias horizontais acrescidas do raio de halo do filtro (1 ou 2 linhas).",
        "Cada processo MPI aplica o filtro localmente de forma independente e devolve apenas a região válida.",
        "Suporta modo multicore local ou niodev em rede configurável diretamente no diálogo de Conexão."
      ],
      classes: "MpjClusterEngine · MpjWorkerMain · WebStudioServer",
      demoModule: "benchmark"
    },
    5: {
      badge: "Membro 5",
      title: "GPU OpenCL, Verifier & Benchmarks",
      focus: "Aceleração massiva via kernels OpenCL (JOCL), validação determinística pixel-a-pixel e métricas de Speedup.",
      points: [
        "O kernel filters.cl executa Blur 5×5, Sharpen 3×3 e Sobel 3×3 em paralelo por pixel na GPU.",
        "O PixelVerifier compara cada saída contra o baseline Sequencial e gera o heatmap Magenta (#FF00FF).",
        "O BenchmarkRunner consolida computação, tempo total RMI+TCP, Speedup e Eficiência em CSV/TXT."
      ],
      classes: "OpenClGpuEngine · filters.cl · PixelVerifier · BenchmarkRunner",
      demoModule: "benchmark"
    }
  };

  const PRESETS = {
    fast: { count: 4, width: 256, height: 256 },
    medium: { count: 8, width: 512, height: 512 },
    full: { count: 20, width: 4000, height: 3000 }
  };

  const ENGINE_LABELS = {
    SEQUENTIAL: "Sequencial",
    THREADS_ROWS: "Threads Linhas",
    THREADS_BLOCKS: "Threads Blocos",
    MPJ: "MPJ Express",
    MPJ_EXPRESS: "MPJ Express",
    MPJ_LOCAL: "MPJ Express",
    GPU: "GPU OpenCL",
    GPU_OPENCL: "GPU OpenCL"
  };

  const state = {
    activeModule: "arch",
    activeMember: 1,
    presenting: false,
    status: null,
    studio: {
      sessionId: null,
      fileName: null,
      width: 0,
      height: 0,
      inputUrl: null,
      outputUrl: null,
      diffUrl: null,
      filter: "BLUR",
      mode: "THREADS_BLOCKS",
      viewMode: "curtain",
      splitPct: 50,
      processing: false
    },
    benchmark: {
      preset: "fast",
      running: false,
      result: null,
      sortKey: "computeMs",
      sortDir: "asc"
    },
    concurrency: {
      clients: 4,
      running: false,
      result: null
    },
    logs: {
      open: false,
      items: [],
      lastId: 0,
      pollTimer: null
    }
  };

  // =========================================================================
  // Helpers & Safe DOM Builders
  // =========================================================================

  const $ = (sel) => document.querySelector(sel);
  const $$ = (sel) => Array.from(document.querySelectorAll(sel));

  function escapeHtml(value) {
    return String(value ?? "")
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;")
      .replace(/'/g, "&#39;");
  }

  function formatMs(val) {
    if (val === null || val === undefined || Number.isNaN(Number(val))) return "—";
    return `${Number(val).toLocaleString("pt-BR")} ms`;
  }

  function formatInt(val) {
    if (val === null || val === undefined || Number.isNaN(Number(val))) return "—";
    return Number(val).toLocaleString("pt-BR");
  }

  function engineDisplayName(raw) {
    if (!raw) return "—";
    const key = String(raw).trim().toUpperCase();
    return ENGINE_LABELS[key] || String(raw);
  }

  async function apiFetch(url, options = {}) {
    const res = await fetch(url, options);
    let data = null;
    try {
      data = await res.json();
    } catch {
      data = null;
    }
    if (!res.ok || (data && data.ok === false)) {
      const msg = (data && data.error) ? data.error : `Erro HTTP ${res.status}`;
      throw new Error(msg);
    }
    return data;
  }

  function showToast(message, tone = "info") {
    const region = $("#toast-region");
    if (!region) return;
    const toast = document.createElement("div");
    toast.className = "toast-item";
    toast.dataset.tone = tone;
    toast.textContent = message;
    region.appendChild(toast);
    setTimeout(() => {
      toast.remove();
    }, 4200);
  }

  function setButtonLoading(button, labelEl, busy, busyText, idleText) {
    button.disabled = busy;
    const spinner = button.querySelector(".btn-spinner");
    if (spinner) {
      spinner.hidden = !busy;
    }
    if (labelEl) {
      labelEl.textContent = busy ? busyText : idleText;
    }
  }

  function svgEl(tag, attrs = {}) {
    const el = document.createElementNS(SVG_NS, tag);
    for (const [k, v] of Object.entries(attrs)) {
      el.setAttribute(k, String(v));
    }
    return el;
  }

  // =========================================================================
  // Theme (dark-mode-toggle)
  // =========================================================================

  function applyTheme(theme, persist = false) {
    const normalized = theme === "light" ? "light" : "dark";
    const meta = document.querySelector('meta[name="color-scheme"]');
    if (meta) meta.content = normalized;
    document.documentElement.dataset.theme = normalized;

    const btn = $("#btn-theme");
    const label = $("#theme-label");
    if (btn) {
      btn.setAttribute("aria-pressed", String(normalized === "light"));
    }
    if (label) {
      label.textContent = normalized === "dark" ? "Claro" : "Escuro";
    }

    if (persist) {
      try {
        localStorage.setItem("color-scheme", normalized);
      } catch {
        // Ignore storage blocked environments
      }
    }
  }

  function toggleTheme() {
    const current = document.documentElement.dataset.theme === "light" ? "light" : "dark";
    applyTheme(current === "dark" ? "light" : "dark", true);
  }

  window.addEventListener("storage", (ev) => {
    if (ev.key === "color-scheme") {
      const next = ev.newValue === "light" ? "light" : "dark";
      applyTheme(next, false);
    }
  });

  // =========================================================================
  // Dialog Light-Dismiss & ReturnValue Lifecycle
  // =========================================================================

  function wireDialog(dialog, onClose) {
    if (!dialog) return;

    dialog.addEventListener("close", () => {
      const rv = dialog.returnValue;
      dialog.returnValue = "";
      if (typeof onClose === "function") {
        onClose(rv);
      }
    });

    if (!("closedBy" in HTMLDialogElement.prototype)) {
      let downOutside = false;
      const isOutsideRect = (ev) => {
        const rect = dialog.getBoundingClientRect();
        return (
          ev.clientY < rect.top ||
          ev.clientY > rect.bottom ||
          ev.clientX < rect.left ||
          ev.clientX > rect.right
        );
      };

      dialog.addEventListener("pointerdown", (ev) => {
        downOutside = ev.target === dialog && isOutsideRect(ev);
      });

      dialog.addEventListener("pointerup", (ev) => {
        if (downOutside && ev.target === dialog && isOutsideRect(ev)) {
          dialog.close("dismiss");
        }
        downOutside = false;
      });
    }
  }

  // =========================================================================
  // Navigation & Presentation Mode
  // =========================================================================

  function switchModule(modId) {
    if (!MODULE_META[modId]) return;
    state.activeModule = modId;

    $$(".module-panel").forEach((panel) => {
      const match = panel.dataset.modulePanel === modId;
      panel.hidden = !match;
    });

    $$(".nav-item").forEach((btn) => {
      if (btn.dataset.module === modId) {
        btn.setAttribute("aria-current", "page");
      } else {
        btn.removeAttribute("aria-current");
      }
    });

    $$(".present-btn").forEach((btn) => {
      btn.classList.toggle("is-active", btn.dataset.module === modId);
    });

    const meta = MODULE_META[modId];
    $("#topbar-title").textContent = meta.title;
    $("#topbar-subtitle").textContent = meta.subtitle;
    hideTooltip();
  }

  function setPresentationMode(enabled) {
    state.presenting = Boolean(enabled);
    document.body.classList.toggle("is-presenting", state.presenting);
    $("#present-bar").hidden = !state.presenting;
    $("#btn-present").setAttribute("aria-pressed", String(state.presenting));
  }

  // =========================================================================
  // Status, Config & Coordinator Dialog
  // =========================================================================

  function renderStatus(status) {
    state.status = status;
    const cfg = status.config || {};
    const pill = $("#btn-connection");
    const label = $("#conn-label");
    const ping = status.lastPing;

    const online = Boolean(status.embeddedRunning || (ping && ping.ok));
    pill.dataset.state = online ? "online" : "offline";

    const latencyPart = ping && ping.ok ? ` · ${ping.latencyMs}ms` : "";
    if (online) {
      label.textContent = `Online · ${cfg.host}:${cfg.rmiPort}${latencyPart}`;
    } else {
      label.textContent = `Desconectado · ${cfg.host}:${cfg.rmiPort}`;
    }

    // Update hardware chip in Module 01
    const hwChip = $("#arch-hw-chip");
    if (hwChip) {
      const ocl = status.openClAvailable ? "Disponível" : "Indisponível";
      hwChip.textContent = `CPU: ${status.cpuCores} cores · OpenCL: ${ocl}`;
    }

    // Update Studio & Concurrency GPU warnings + CPU defaults on first load
    const gpuWarn = $("#studio-gpu-warning");
    const gpuMeta = $("#studio-gpu-meta");
    if (gpuWarn && gpuMeta) {
      gpuWarn.hidden = Boolean(status.openClAvailable);
      gpuMeta.textContent = status.openClAvailable ? "JOCL · kernel ativo" : "OpenCL indisponível";
    }

    if (!state._defaultsApplied && status.cpuCores) {
      const defThreads = Math.max(1, Math.min(64, Number(status.cpuCores)));
      const defMpi = Math.max(1, Math.min(4, defThreads));
      $("#studio-threads").value = String(defThreads);
      $("#studio-mpi").value = String(defMpi);
      $("#bench-threads").value = String(defThreads);
      $("#bench-mpi").value = String(defMpi);
      state._defaultsApplied = true;
    }

    // Semaphore limit in Concurrency
    const semText = `${cfg.maxConcurrent || 2} simultâneos`;
    $("#conc-sem-limit").textContent = semText;

    // Dialog banner
    const dStatus = $("#dialog-status-text");
    const dPing = $("#dialog-ping-text");
    if (dStatus) {
      dStatus.textContent = status.embeddedRunning
        ? `Coordenador ativo (${status.embeddedHost}:${status.embeddedRmiPort} / TCP ${status.embeddedTcpPort})`
        : "Coordenador local parado";
    }
    if (dPing) {
      dPing.textContent = ping
        ? (ping.ok ? `Ping OK (${ping.latencyMs} ms às ${ping.checkedAt})` : `Ping falhou (${ping.checkedAt})`)
        : "Sem ping recente";
    }

    // Logs badge
    if (typeof status.logCount === "number") {
      $("#logs-count-badge").textContent = String(status.logCount);
    }
  }

  function populateConnectionForm() {
    const cfg = (state.status && state.status.config) || {};
    $("#cfg-host").value = cfg.host || "127.0.0.1";
    $("#cfg-rmi-port").value = String(cfg.rmiPort || 1099);
    $("#cfg-tcp-port").value = String(cfg.tcpPort || 9090);
    $("#cfg-max-concurrent").value = String(cfg.maxConcurrent || 2);
    $("#cfg-mpi-dev").value = cfg.mpiDev || "multicore";
  }

  function readConnectionForm() {
    return {
      host: $("#cfg-host").value.trim(),
      rmiPort: Number($("#cfg-rmi-port").value),
      tcpPort: Number($("#cfg-tcp-port").value),
      maxConcurrent: Number($("#cfg-max-concurrent").value),
      mpiDev: $("#cfg-mpi-dev").value
    };
  }

  async function refreshStatus() {
    try {
      const data = await apiFetch("/api/status");
      renderStatus(data);
    } catch (err) {
      $("#btn-connection").dataset.state = "offline";
      $("#conn-label").textContent = "Erro ao consultar API";
      showToast(err.message, "error");
    }
  }

  // =========================================================================
  // Module 01: Arquitetura & Membros
  // =========================================================================

  function selectMember(memberId) {
    const id = Number(memberId);
    const info = MEMBER_SCRIPTS[id];
    if (!info) return;
    state.activeMember = id;

    $$(".member-card").forEach((card) => {
      const active = Number(card.dataset.member) === id;
      card.classList.toggle("is-active", active);
      card.setAttribute("aria-pressed", String(active));
    });

    const svg = $("#arch-svg");
    svg.classList.add("has-focus");
    svg.querySelectorAll("[data-members]").forEach((el) => {
      const list = el.dataset.members.split(",").map((n) => Number(n.trim()));
      el.classList.toggle("is-highlighted", list.includes(id));
    });

    $("#script-member-badge").textContent = info.badge;
    $("#script-title").textContent = info.title;
    $("#script-focus").textContent = info.focus;
    $("#script-classes").textContent = info.classes;

    const ul = $("#script-points");
    ul.textContent = "";
    info.points.forEach((pt) => {
      const li = document.createElement("li");
      li.textContent = pt;
      ul.appendChild(li);
    });

    $("#btn-goto-demo").dataset.targetModule = info.demoModule;
  }

  // =========================================================================
  // Module 02: Estúdio Visual
  // =========================================================================

  function setStudioViewMode(mode) {
    state.studio.viewMode = mode;
    $$("#stage-toolbar .seg-btn").forEach((btn) => {
      const active = btn.dataset.viewMode === mode;
      btn.classList.toggle("is-active", active);
      btn.setAttribute("aria-pressed", String(active));
    });

    if (!state.studio.inputUrl) return;
    $("#view-curtain").hidden = mode !== "curtain";
    $("#view-side").hidden = mode !== "side";
    $("#view-diff").hidden = mode !== "diff";
  }

  function updateCurtainSplit(pct) {
    const clamped = Math.max(0, Math.min(100, Number(pct)));
    state.studio.splitPct = clamped;
    const curtain = $("#view-curtain");
    const range = $("#curtain-range");
    curtain.style.setProperty("--split", `${clamped}%`);
    range.value = String(Math.round(clamped));
    range.style.setProperty("--progress", `${clamped}%`);
  }

  function applyStudioLoadedImage(res) {
    state.studio.sessionId = res.sessionId;
    state.studio.fileName = res.fileName;
    state.studio.width = res.width;
    state.studio.height = res.height;
    state.studio.inputUrl = res.inputUrl;
    state.studio.outputUrl = null;
    state.studio.diffUrl = null;

    $("#studio-dropzone").hidden = true;
    $("#stage-toolbar").hidden = false;
    $("#stage-file-info").textContent = `${res.fileName} · ${res.width}×${res.height} px`;
    $("#btn-studio-process").disabled = false;

    const curtain = $("#view-curtain");
    curtain.style.setProperty("--stage-ratio", `${res.width} / ${res.height}`);

    const cacheBust = `?t=${Date.now()}`;
    $("#img-curtain-before").src = res.inputUrl + cacheBust;
    $("#img-curtain-after").src = res.inputUrl + cacheBust;
    $("#img-side-before").src = res.inputUrl + cacheBust;
    $("#img-side-after").src = res.inputUrl + cacheBust;
    $("#img-diff-map").removeAttribute("src");
    $("#diff-empty-note").hidden = false;
    $("#diff-legend-count").textContent = "0 / 0 px";

    $("#tag-curtain-left").textContent = "Aguardando filtro";
    $("#tag-curtain-right").textContent = "Original";
    updateCurtainSplit(50);

    // Reset KPIs
    $("#studio-kpi-compute").textContent = "—";
    $("#studio-kpi-total").textContent = "—";
    $("#studio-kpi-diff").textContent = "—";
    setVerifiedBadge($("#studio-kpi-verified"), "neutral", "Pronto p/ processar");

    setStudioViewMode("curtain");
  }

  function setVerifiedBadge(container, tone, text) {
    container.textContent = "";
    const badge = document.createElement("span");
    badge.className = "status-badge";
    badge.dataset.tone = tone;

    const svg = svgEl("svg", {
      width: 14,
      height: 14,
      viewBox: "0 0 24 24",
      fill: "none",
      stroke: "currentColor",
      "stroke-width": 2,
      "stroke-linecap": "round",
      "stroke-linejoin": "round",
      "aria-hidden": "true"
    });
    if (tone === "success") {
      svg.appendChild(svgEl("polyline", { points: "20 6 9 17 4 12" }));
    } else if (tone === "danger") {
      svg.appendChild(svgEl("circle", { cx: 12, cy: 12, r: 9 }));
      svg.appendChild(svgEl("line", { x1: 15, y1: 9, x2: 9, y2: 15 }));
      svg.appendChild(svgEl("line", { x1: 9, y1: 9, x2: 15, y2: 15 }));
    } else {
      svg.appendChild(svgEl("circle", { cx: 12, cy: 12, r: 9 }));
    }
    const span = document.createElement("span");
    span.textContent = text;
    badge.appendChild(svg);
    badge.appendChild(span);
    container.appendChild(badge);
  }

  async function generateSyntheticImage() {
    const size = Number($("#studio-synth-size").value || 384);
    try {
      const data = await apiFetch("/api/studio/generate", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ width: size, height: size })
      });
      applyStudioLoadedImage(data);
      showToast(`Imagem sintética ${data.width}×${data.height} carregada.`, "success");
      refreshStatus();
    } catch (err) {
      showToast(err.message, "error");
    }
  }

  async function uploadPhotoFile(file) {
    if (!file) return;
    const safeHeaderName = (file.name || "upload.png").replace(/[^\x20-\x7E]/g, "_");
    try {
      const data = await apiFetch(`/api/studio/upload?filename=${encodeURIComponent(safeHeaderName)}`, {
        method: "POST",
        headers: {
          "Content-Type": file.type || "application/octet-stream",
          "X-Filename": safeHeaderName
        },
        body: file
      });
      applyStudioLoadedImage(data);
      showToast(`Foto "${data.fileName}" enviada (${data.width}×${data.height}).`, "success");
      refreshStatus();
    } catch (err) {
      showToast(err.message, "error");
    }
  }

  async function processStudioImage() {
    if (!state.studio.sessionId || state.studio.processing) return;
    state.studio.processing = true;
    const btn = $("#btn-studio-process");
    const lbl = $("#btn-studio-process-label");
    setButtonLoading(btn, lbl, true, "Processando via RMI/TCP…", "Processar via RMI/TCP");

    try {
      const payload = {
        sessionId: state.studio.sessionId,
        filter: state.studio.filter,
        mode: state.studio.mode,
        threads: Number($("#studio-threads").value || 4),
        tileSize: Number($("#studio-tile").value || 64),
        mpiProcesses: Number($("#studio-mpi").value || 4),
        computeDiff: $("#studio-compute-diff").checked
      };
      const res = await apiFetch("/api/studio/process", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload)
      });

      state.studio.outputUrl = res.outputUrl;
      state.studio.diffUrl = res.diffUrl;

      const bust = `?t=${Date.now()}`;
      $("#img-curtain-before").src = res.inputUrl;
      $("#img-curtain-after").src = res.outputUrl + bust;
      $("#img-side-before").src = res.inputUrl;
      $("#img-side-after").src = res.outputUrl + bust;
      $("#tag-curtain-left").textContent = `Depois (${res.filter} · ${engineDisplayName(res.mode)})`;
      $("#tag-curtain-right").textContent = "Antes (Original)";

      if (res.diffUrl) {
        $("#img-diff-map").src = res.diffUrl + bust;
        $("#img-diff-map").hidden = false;
        $("#diff-empty-note").hidden = true;
      } else {
        $("#img-diff-map").hidden = true;
        $("#diff-empty-note").hidden = false;
      }
      $("#diff-legend-count").textContent = `${formatInt(res.diffPixels)} / ${formatInt(res.totalPixels)} px`;

      $("#studio-kpi-compute").textContent = formatMs(res.computeMs);
      $("#studio-kpi-total").textContent = formatMs(res.totalMs);
      $("#studio-kpi-diff").textContent = `${formatInt(res.diffPixels)} / ${formatInt(res.totalPixels)}`;

      if (res.verified) {
        setVerifiedBadge($("#studio-kpi-verified"), "success", "Aprovado · 0 divergências");
      } else {
        setVerifiedBadge($("#studio-kpi-verified"), "danger", `Divergente · ${formatInt(res.diffPixels)} px`);
      }

      showToast(`Concluído (${res.jobId}) em ${res.totalMs} ms.`, "success");
      refreshStatus();
      if (state.logs.open) fetchLogs();
    } catch (err) {
      showToast(err.message, "error");
    } finally {
      state.studio.processing = false;
      setButtonLoading(btn, lbl, false, "Processando via RMI/TCP…", "Processar via RMI/TCP");
      btn.disabled = !state.studio.sessionId;
    }
  }

  // =========================================================================
  // Shared Interactive SVG Tooltip
  // =========================================================================

  function showTooltip(targetEl, lines) {
    const tip = $("#svg-tooltip");
    if (!tip) return;
    tip.textContent = "";
    lines.forEach((line, idx) => {
      const div = document.createElement("div");
      if (idx === 0) div.style.fontWeight = "700";
      div.textContent = line;
      tip.appendChild(div);
    });
    tip.hidden = false;

    const rect = targetEl.getBoundingClientRect();
    const tipRect = tip.getBoundingClientRect();
    let left = rect.left + rect.width / 2 - tipRect.width / 2;
    let top = rect.top - tipRect.height - 10;
    if (top < 8) top = rect.bottom + 10;
    left = Math.max(8, Math.min(window.innerWidth - tipRect.width - 8, left));
    tip.style.left = `${Math.round(left)}px`;
    tip.style.top = `${Math.round(top)}px`;
  }

  function hideTooltip() {
    const tip = $("#svg-tooltip");
    if (tip) tip.hidden = true;
  }

  function attachTooltipEvents(el, lines) {
    el.classList.add("chart-focusable");
    el.setAttribute("tabindex", "0");
    el.setAttribute("aria-label", lines.join(" — "));
    el.addEventListener("mouseenter", () => showTooltip(el, lines));
    el.addEventListener("focus", () => showTooltip(el, lines));
    el.addEventListener("mouseleave", hideTooltip);
    el.addEventListener("blur", hideTooltip);
  }

  // =========================================================================
  // Module 03: Benchmarks Comparativos
  // =========================================================================

  function selectBenchmarkPreset(presetKey) {
    const preset = PRESETS[presetKey];
    if (!preset) return;
    state.benchmark.preset = presetKey;
    $$(".preset-card").forEach((card) => {
      const active = card.dataset.preset === presetKey;
      card.classList.toggle("is-selected", active);
      card.setAttribute("aria-checked", String(active));
    });
    $("#bench-count").value = String(preset.count);
    $("#bench-width").value = String(preset.width);
    $("#bench-height").value = String(preset.height);
  }

  function isHeavyThermalBatch(count, width, height) {
    return count >= 10 && Math.max(width, height) >= 2000;
  }

  function requestBenchmarkRun() {
    if (state.benchmark.running) return;
    const count = Number($("#bench-count").value || 4);
    const width = Number($("#bench-width").value || 256);
    const height = Number($("#bench-height").value || 256);

    if (state.benchmark.preset === "full" || isHeavyThermalBatch(count, width, height)) {
      $("#thermal-dialog-msg").textContent =
        `Este benchmark processará ${count} imagens ${width}×${height} px em todos os 5 motores em sequência, exigindo alto uso sustentado de CPU/GPU e memória.`;
      $("#thermal-dialog").showModal();
      return;
    }
    executeBenchmarkRun();
  }

  async function executeBenchmarkRun() {
    if (state.benchmark.running) return;
    state.benchmark.running = true;
    const btn = $("#btn-bench-run");
    const lbl = $("#btn-bench-run-label");
    setButtonLoading(btn, lbl, true, "Executando 5 motores…", "Executar Benchmark Completo");

    try {
      const payload = {
        count: Number($("#bench-count").value || 4),
        width: Number($("#bench-width").value || 256),
        height: Number($("#bench-height").value || 256),
        filter: $("#bench-filter").value,
        threads: Number($("#bench-threads").value || 4),
        tileSize: Number($("#bench-tile").value || 64),
        mpiProcesses: Number($("#bench-mpi").value || 4)
      };

      const data = await apiFetch("/api/benchmark/run", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload)
      });

      state.benchmark.result = data;
      renderBenchmarkResult(data);
      showToast(`Benchmark ${data.jobId} concluído em ${formatMs(data.elapsedMs)}.`, "success");
      refreshStatus();
      if (state.logs.open) fetchLogs();
    } catch (err) {
      showToast(err.message, "error");
    } finally {
      state.benchmark.running = false;
      setButtonLoading(btn, lbl, false, "Executando 5 motores…", "Executar Benchmark Completo");
    }
  }

  function renderBenchmarkResult(data) {
    const rows = Array.isArray(data.rows) ? data.rows : [];
    const winner = data.winner;
    const banner = $("#bench-winner-banner");

    if (winner) {
      banner.hidden = false;
      $("#winner-name").textContent = engineDisplayName(winner.versionName);
      $("#winner-compute").textContent = formatMs(winner.computeMs);
      $("#winner-speedup").textContent = `${Number(winner.speedup).toFixed(2)}×`;
      $("#winner-status").textContent = winner.verified ? "Verificado (0 diff)" : `Diff: ${winner.diffPixels}`;
    } else {
      banner.hidden = true;
    }

    $("#bench-meta-summary").textContent =
      `Job ${data.jobId} · ${data.filter} · ${data.count}× ${data.width}×${data.height} px · Total: ${formatMs(data.elapsedMs)}`;

    $("#btn-export-csv").disabled = !data.csvUrl && !data.csvText;
    $("#btn-export-txt").disabled = !data.txtUrl && !data.txtText;

    renderTimeChart(rows);
    renderSpeedupChart(rows);
    renderBenchmarkTable();
  }

  function renderTimeChart(rows) {
    const host = $("#chart-time-host");
    host.textContent = "";
    if (!rows.length) {
      const p = document.createElement("p");
      p.className = "empty-chart-msg";
      p.textContent = "Sem linhas de benchmark retornadas.";
      host.appendChild(p);
      return;
    }

    const sorted = [...rows].sort((a, b) => a.computeMs - b.computeMs);
    const maxVal = Math.max(1, ...sorted.map((r) => Math.max(r.computeMs, r.totalMs)));
    const rowH = 44;
    const padTop = 14;
    const padLeft = 128;
    const padRight = 72;
    const width = 560;
    const barAreaW = width - padLeft - padRight;
    const height = padTop + sorted.length * rowH + 16;

    const svg = svgEl("svg", {
      viewBox: `0 0 ${width} ${height}`,
      class: "chart-svg",
      role: "img",
      "aria-label": "Gráfico de barras horizontais comparando tempo de computação e tempo total em milissegundos"
    });

    sorted.forEach((row, i) => {
      const y = padTop + i * rowH;
      const g = svgEl("g");
      const label = svgEl("text", {
        x: padLeft - 10,
        y: y + 19,
        "text-anchor": "end",
        fill: "currentColor",
        "font-size": "12",
        "font-weight": "600"
      });
      label.textContent = engineDisplayName(row.versionName);
      g.appendChild(label);

      const totalW = Math.max(3, (row.totalMs / maxVal) * barAreaW);
      const compW = Math.max(3, (row.computeMs / maxVal) * barAreaW);

      const barComp = svgEl("rect", {
        x: padLeft,
        y: y + 4,
        width: compW.toFixed(1),
        height: 12,
        rx: 3,
        fill: "var(--primary)"
      });

      const barTotal = svgEl("rect", {
        x: padLeft,
        y: y + 19,
        width: totalW.toFixed(1),
        height: 10,
        rx: 3,
        fill: "var(--accent)",
        opacity: "0.85"
      });

      const valTxt = svgEl("text", {
        x: padLeft + Math.max(compW, totalW) + 8,
        y: y + 18,
        fill: "var(--text-muted)",
        "font-size": "11",
        "font-family": "var(--font-mono)"
      });
      valTxt.textContent = `${row.computeMs} / ${row.totalMs} ms`;

      g.appendChild(barComp);
      g.appendChild(barTotal);
      g.appendChild(valTxt);

      attachTooltipEvents(g, [
        `${engineDisplayName(row.versionName)} (${row.workers} workers)`,
        `Computação: ${formatMs(row.computeMs)}`,
        `Total RMI+TCP: ${formatMs(row.totalMs)}`,
        `Verificado: ${row.verified ? "Sim (0 diff)" : `Não (${row.diffPixels} px)`}`
      ]);
      svg.appendChild(g);
    });

    host.appendChild(svg);
  }

  function renderSpeedupChart(rows) {
    const host = $("#chart-speedup-host");
    host.textContent = "";
    if (!rows.length) {
      const p = document.createElement("p");
      p.className = "empty-chart-msg";
      p.textContent = "Sem linhas de benchmark retornadas.";
      host.appendChild(p);
      return;
    }

    const sorted = [...rows].sort((a, b) => b.speedup - a.speedup);
    const maxSpeedup = Math.max(1.5, ...sorted.map((r) => Number(r.speedup) || 0));
    const rowH = 44;
    const padTop = 22;
    const padLeft = 128;
    const padRight = 96;
    const width = 560;
    const barAreaW = width - padLeft - padRight;
    const height = padTop + sorted.length * rowH + 18;

    const svg = svgEl("svg", {
      viewBox: `0 0 ${width} ${height}`,
      class: "chart-svg",
      role: "img",
      "aria-label": "Gráfico de barras de Speedup comparado ao baseline 1.0x e Eficiência percentual"
    });

    // 1.0x dashed reference line
    const xRef = padLeft + (1.0 / maxSpeedup) * barAreaW;
    svg.appendChild(svgEl("line", {
      x1: xRef.toFixed(1),
      y1: 12,
      x2: xRef.toFixed(1),
      y2: height - 10,
      stroke: "var(--warning)",
      "stroke-width": 1.5,
      "stroke-dasharray": "4 4"
    }));

    const refTxt = svgEl("text", {
      x: xRef.toFixed(1),
      y: 10,
      "text-anchor": "middle",
      fill: "var(--warning)",
      "font-size": "10",
      "font-weight": "700"
    });
    refTxt.textContent = "1.0×";
    svg.appendChild(refTxt);

    sorted.forEach((row, i) => {
      const y = padTop + i * rowH;
      const g = svgEl("g");
      const label = svgEl("text", {
        x: padLeft - 10,
        y: y + 20,
        "text-anchor": "end",
        fill: "currentColor",
        "font-size": "12",
        "font-weight": "600"
      });
      label.textContent = engineDisplayName(row.versionName);
      g.appendChild(label);

      const sp = Math.max(0, Number(row.speedup) || 0);
      const barW = Math.max(3, (sp / maxSpeedup) * barAreaW);
      const fill = sp >= 1.0 ? "var(--success)" : "var(--text-muted)";

      g.appendChild(svgEl("rect", {
        x: padLeft,
        y: y + 7,
        width: barW.toFixed(1),
        height: 18,
        rx: 4,
        fill
      }));

      const valTxt = svgEl("text", {
        x: padLeft + barW + 8,
        y: y + 20,
        fill: "var(--text-muted)",
        "font-size": "11",
        "font-family": "var(--font-mono)"
      });
      valTxt.textContent = `${sp.toFixed(2)}× (${Number(row.efficiencyPct).toFixed(0)}%)`;
      g.appendChild(valTxt);

      attachTooltipEvents(g, [
        `${engineDisplayName(row.versionName)}`,
        `Speedup: ${sp.toFixed(2)}×`,
        `Eficiência: ${Number(row.efficiencyPct).toFixed(1)}%`,
        `Workers: ${row.workers}`
      ]);
      svg.appendChild(g);
    });

    host.appendChild(svg);
  }

  function renderBenchmarkTable() {
    const tbody = $("#bench-tbody");
    tbody.textContent = "";
    const result = state.benchmark.result;
    if (!result || !Array.isArray(result.rows) || result.rows.length === 0) {
      const tr = document.createElement("tr");
      tr.className = "empty-row";
      const td = document.createElement("td");
      td.colSpan = 9;
      td.textContent = "Nenhum benchmark executado nesta sessão.";
      tr.appendChild(td);
      tbody.appendChild(tr);
      return;
    }

    const { sortKey, sortDir } = state.benchmark;
    const rows = [...result.rows].sort((a, b) => {
      const av = a[sortKey];
      const bv = b[sortKey];
      let cmp = 0;
      if (typeof av === "number" && typeof bv === "number") {
        cmp = av - bv;
      } else if (typeof av === "boolean" && typeof bv === "boolean") {
        cmp = Number(av) - Number(bv);
      } else {
        cmp = String(av ?? "").localeCompare(String(bv ?? ""), "pt-BR");
      }
      return sortDir === "asc" ? cmp : -cmp;
    });

    // Update aria-sort attributes
    $$("#bench-table thead th[aria-sort]").forEach((th) => {
      const btn = th.querySelector(".sort-btn");
      if (btn && btn.dataset.sort === sortKey) {
        th.setAttribute("aria-sort", sortDir === "asc" ? "ascending" : "descending");
      } else {
        th.setAttribute("aria-sort", "none");
      }
    });

    rows.forEach((row) => {
      const tr = document.createElement("tr");

      const th = document.createElement("th");
      th.scope = "row";
      th.textContent = engineDisplayName(row.versionName);
      tr.appendChild(th);

      const cells = [
        { text: String(row.workers), num: true },
        { text: formatMs(row.computeMs), num: true },
        { text: formatMs(row.totalMs), num: true },
        { text: `${Number(row.speedup).toFixed(2)}×`, num: true },
        { text: `${Number(row.efficiencyPct).toFixed(1)}%`, num: true },
        { text: formatInt(row.diffPixels), num: true }
      ];

      cells.forEach((c) => {
        const td = document.createElement("td");
        if (c.num) td.className = "tabular-nums";
        td.textContent = c.text;
        tr.appendChild(td);
      });

      const tdVer = document.createElement("td");
      setVerifiedBadge(
        tdVer,
        row.verified ? "success" : "danger",
        row.verified ? "Verificado" : "Divergente"
      );
      tr.appendChild(tdVer);

      const tdNotes = document.createElement("td");
      tdNotes.textContent = row.notes || "—";
      tr.appendChild(tdNotes);

      tbody.appendChild(tr);
    });
  }

  function exportBenchmarkReport(kind) {
    const res = state.benchmark.result;
    if (!res) return;
    const url = kind === "csv" ? res.csvUrl : res.txtUrl;
    const text = kind === "csv" ? res.csvText : res.txtText;

    if (url) {
      const a = document.createElement("a");
      a.href = `${url}?download=1`;
      a.download = `benchmark_report.${kind}`;
      document.body.appendChild(a);
      a.click();
      a.remove();
      return;
    }

    if (text) {
      const blob = new Blob([text], { type: kind === "csv" ? "text/csv;charset=utf-8" : "text/plain;charset=utf-8" });
      const blobUrl = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = blobUrl;
      a.download = `benchmark_report.${kind}`;
      document.body.appendChild(a);
      a.click();
      a.remove();
      URL.revokeObjectURL(blobUrl);
    }
  }

  // =========================================================================
  // Module 04: Concorrência & Semáforo
  // =========================================================================

  function selectConcClients(count) {
    state.concurrency.clients = Number(count);
    $$("#conc-clients-group .seg-btn").forEach((btn) => {
      const active = Number(btn.dataset.clients) === state.concurrency.clients;
      btn.classList.toggle("is-active", active);
      btn.setAttribute("aria-checked", String(active));
    });
  }

  async function runConcurrencyWave() {
    if (state.concurrency.running) return;
    state.concurrency.running = true;
    const btn = $("#btn-conc-run");
    const lbl = $("#btn-conc-run-label");
    setButtonLoading(btn, lbl, true, "Executando onda paralela…", "Disparar Clientes em Paralelo");

    try {
      const defThreads = state.status ? Math.max(1, Number(state.status.cpuCores || 4)) : 4;
      const payload = {
        clients: state.concurrency.clients,
        count: 2,
        width: 128,
        height: 128,
        filter: $("#conc-filter").value,
        mode: $("#conc-mode").value,
        threads: defThreads,
        tileSize: 64,
        mpiProcesses: Math.min(4, defThreads)
      };

      const data = await apiFetch("/api/concurrency/run", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload)
      });

      state.concurrency.result = data;
      renderConcurrencyResult(data);
      showToast(`Onda concluída: ${data.succeeded}/${data.clientsRequested} sucesso(s).`, "success");
      refreshStatus();
      if (state.logs.open) fetchLogs();
    } catch (err) {
      showToast(err.message, "error");
    } finally {
      state.concurrency.running = false;
      setButtonLoading(btn, lbl, false, "Executando onda paralela…", "Disparar Clientes em Paralelo");
    }
  }

  function renderConcurrencyResult(data) {
    $("#conc-kpi-wave").textContent = formatMs(data.waveElapsedMs);
    $("#conc-kpi-limit").textContent = `${data.maxConcurrentConfigured} slots`;
    $("#conc-kpi-done").textContent = `${data.succeeded} / ${data.clientsRequested}`;
    const rate = data.clientsRequested > 0
      ? Math.round((data.succeeded / data.clientsRequested) * 100)
      : 0;
    $("#conc-kpi-rate").textContent = `${rate}%`;

    const host = $("#gantt-host");
    host.textContent = "";
    const items = Array.isArray(data.items) ? data.items : [];
    if (!items.length) {
      const p = document.createElement("p");
      p.className = "empty-chart-msg";
      p.textContent = "Sem eventos de clientes nesta onda.";
      host.appendChild(p);
      return;
    }

    const totalSpan = Math.max(10, Number(data.waveElapsedMs) || 10, ...items.map((it) => Number(it.endOffsetMs) || 0));
    const rowH = 38;
    const padTop = 26;
    const padLeft = 100;
    const padRight = 32;
    const width = 760;
    const axisW = width - padLeft - padRight;
    const height = padTop + items.length * rowH + 22;

    const svg = svgEl("svg", {
      viewBox: `0 0 ${width} ${height}`,
      class: "chart-svg",
      role: "img",
      "aria-label": "Gráfico Gantt mostrando início, fim e duração de cada cliente paralelo sob o semáforo"
    });

    // Vertical grid ticks (0%, 25%, 50%, 75%, 100%)
    [0, 0.25, 0.5, 0.75, 1].forEach((frac) => {
      const x = padLeft + frac * axisW;
      svg.appendChild(svgEl("line", {
        x1: x.toFixed(1),
        y1: 18,
        x2: x.toFixed(1),
        y2: height - 10,
        stroke: "var(--border)",
        "stroke-dasharray": "3 3"
      }));
      const t = svgEl("text", {
        x: x.toFixed(1),
        y: 12,
        "text-anchor": "middle",
        fill: "var(--text-muted)",
        "font-size": "10",
        "font-family": "var(--font-mono)"
      });
      t.textContent = `${Math.round(totalSpan * frac)} ms`;
      svg.appendChild(t);
    });

    items.forEach((it, idx) => {
      const y = padTop + idx * rowH;
      const label = svgEl("text", {
        x: padLeft - 10,
        y: y + 20,
        "text-anchor": "end",
        fill: "currentColor",
        "font-size": "12",
        "font-weight": "600"
      });
      label.textContent = `Cliente ${it.clientIndex}`;
      svg.appendChild(label);

      const startMs = Math.max(0, Number(it.startOffsetMs) || 0);
      const durMs = Math.max(1, Number(it.durationMs) || 1);
      const x = padLeft + (startMs / totalSpan) * axisW;
      const w = Math.max(8, (durMs / totalSpan) * axisW);
      const ok = it.status === "COMPLETED";

      const barGroup = svgEl("g");
      barGroup.appendChild(svgEl("rect", {
        x: x.toFixed(1),
        y: y + 5,
        width: w.toFixed(1),
        height: 22,
        rx: 4,
        fill: ok ? "var(--success)" : "var(--danger)"
      }));

      const barText = svgEl("text", {
        x: (x + 6).toFixed(1),
        y: y + 20,
        fill: "#FFFFFF",
        "font-size": "10",
        "font-weight": "700",
        "font-family": "var(--font-mono)"
      });
      barText.textContent = w > 54 ? `${durMs} ms` : (ok ? "OK" : "ERR");
      barGroup.appendChild(barText);

      const details = [
        `Cliente ${it.clientIndex} · ${ok ? "Sucesso" : "Erro"}`,
        `jobId: ${it.jobId || "—"}`,
        `Início: ${it.startOffsetMs} ms · Fim: ${it.endOffsetMs} ms`,
        `Duração: ${it.durationMs} ms`
      ];
      if (it.error) details.push(`Erro: ${it.error}`);
      attachTooltipEvents(barGroup, details);

      svg.appendChild(barGroup);
    });

    host.appendChild(svg);
  }

  // =========================================================================
  // Logs RMI/TCP Drawer
  // =========================================================================

  function renderLogs() {
    const body = $("#logs-body");
    const term = ($("#logs-search").value || "").trim().toLowerCase();
    const filtered = state.logs.items.filter((entry) => {
      if (!term) return true;
      return (
        String(entry.level).toLowerCase().includes(term) ||
        String(entry.source).toLowerCase().includes(term) ||
        String(entry.message).toLowerCase().includes(term)
      );
    });

    $("#logs-count-badge").textContent = String(state.logs.items.length);
    $("#logs-drawer-count").textContent = String(filtered.length);

    body.textContent = "";
    if (!filtered.length) {
      const p = document.createElement("p");
      p.className = "logs-empty";
      p.textContent = state.logs.items.length
        ? "Nenhum log corresponde ao filtro."
        : "Nenhum log registrado.";
      body.appendChild(p);
      return;
    }

    filtered.forEach((entry) => {
      const row = document.createElement("div");
      row.className = "log-line";

      const t = document.createElement("span");
      t.className = "log-time tabular-nums";
      t.textContent = entry.time;

      const lvl = document.createElement("span");
      lvl.className = "log-level";
      lvl.dataset.level = entry.level;
      lvl.textContent = entry.level;

      const src = document.createElement("span");
      src.className = "log-source";
      src.textContent = entry.source;

      const msg = document.createElement("span");
      msg.className = "log-msg";
      msg.textContent = entry.message;

      row.appendChild(t);
      row.appendChild(lvl);
      row.appendChild(src);
      row.appendChild(msg);
      body.appendChild(row);
    });

    body.scrollTop = body.scrollHeight;
  }

  async function fetchLogs(reset = false) {
    if (reset) {
      state.logs.items = [];
      state.logs.lastId = 0;
    }
    try {
      const data = await apiFetch(`/api/logs?after=${state.logs.lastId}`);
      const incoming = Array.isArray(data.logs) ? data.logs : [];
      if (incoming.length > 0) {
        state.logs.items.push(...incoming);
        if (state.logs.items.length > 300) {
          state.logs.items = state.logs.items.slice(-300);
        }
        state.logs.lastId = incoming[incoming.length - 1].id;
      }
      renderLogs();
    } catch (err) {
      showToast(err.message, "error");
    }
  }

  function toggleLogsDrawer(forceOpen) {
    const next = typeof forceOpen === "boolean" ? forceOpen : !state.logs.open;
    state.logs.open = next;
    $("#logs-drawer").hidden = !next;
    $("#btn-logs-toggle").setAttribute("aria-expanded", String(next));

    if (state.logs.pollTimer) {
      clearInterval(state.logs.pollTimer);
      state.logs.pollTimer = null;
    }

    if (next) {
      fetchLogs(false);
      state.logs.pollTimer = setInterval(() => fetchLogs(false), 2500);
    }
  }

  // =========================================================================
  // Event Wiring & Bootstrap
  // =========================================================================

  function bindEvents() {
    // Theme
    const initialTheme = document.documentElement.dataset.theme === "light" ? "light" : "dark";
    applyTheme(initialTheme, false);
    $("#btn-theme").addEventListener("click", toggleTheme);

    // Navigation
    $$(".nav-item, .present-btn").forEach((btn) => {
      btn.addEventListener("click", () => switchModule(btn.dataset.module));
    });

    // Presentation mode
    $("#btn-present").addEventListener("click", () => setPresentationMode(!state.presenting));
    $("#btn-present-exit").addEventListener("click", () => setPresentationMode(false));

    // Connection dialog
    const connDialog = $("#connection-dialog");
    wireDialog(connDialog, null);
    $("#btn-connection").addEventListener("click", () => {
      populateConnectionForm();
      connDialog.showModal();
    });
    $("#btn-adjust-semaphore").addEventListener("click", () => {
      populateConnectionForm();
      connDialog.showModal();
    });

    $("#btn-cfg-ping").addEventListener("click", async () => {
      try {
        const { host, rmiPort } = readConnectionForm();
        const res = await apiFetch("/api/ping", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ host, rmiPort })
        });
        showToast(res.ok ? `Ping OK (${res.latencyMs} ms)` : `Ping falhou: ${res.error}`, res.ok ? "success" : "error");
        refreshStatus();
      } catch (err) {
        showToast(err.message, "error");
      }
    });

    $("#btn-cfg-save").addEventListener("click", async () => {
      try {
        const data = await apiFetch("/api/config", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(readConnectionForm())
        });
        renderStatus(data);
        showToast("Configuração salva.", "success");
      } catch (err) {
        showToast(err.message, "error");
      }
    });

    $("#btn-srv-start").addEventListener("click", async () => {
      try {
        const data = await apiFetch("/api/server/start", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(readConnectionForm())
        });
        renderStatus(data);
        showToast("Coordenador ativo.", "success");
      } catch (err) {
        showToast(err.message, "error");
      }
    });

    $("#btn-srv-stop").addEventListener("click", async () => {
      try {
        const data = await apiFetch("/api/server/stop", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: "{}"
        });
        renderStatus(data);
        showToast("Coordenador parado.", "info");
      } catch (err) {
        showToast(err.message, "error");
      }
    });

    // Thermal dialog
    wireDialog($("#thermal-dialog"), (rv) => {
      if (rv === "confirm") {
        executeBenchmarkRun();
      }
    });

    // Module 01
    $$(".member-card").forEach((card) => {
      card.addEventListener("click", () => selectMember(card.dataset.member));
    });
    $("#btn-goto-demo").addEventListener("click", () => {
      const target = $("#btn-goto-demo").dataset.targetModule || "studio";
      switchModule(target);
    });
    selectMember(1);

    // Module 02: Studio
    const fileInput = $("#studio-file-input");
    $("#btn-upload-photo").addEventListener("click", () => fileInput.click());
    $("#btn-swap-photo").addEventListener("click", () => fileInput.click());
    fileInput.addEventListener("change", () => {
      if (fileInput.files && fileInput.files[0]) {
        uploadPhotoFile(fileInput.files[0]);
        fileInput.value = "";
      }
    });

    $("#btn-gen-synthetic").addEventListener("click", generateSyntheticImage);
    $("#btn-new-synthetic").addEventListener("click", generateSyntheticImage);

    const stageViewport = $("#stage-viewport");
    ["dragenter", "dragover"].forEach((evt) => {
      stageViewport.addEventListener(evt, (e) => {
        e.preventDefault();
        stageViewport.classList.add("is-dragover");
      });
    });
    ["dragleave", "drop"].forEach((evt) => {
      stageViewport.addEventListener(evt, (e) => {
        e.preventDefault();
        stageViewport.classList.remove("is-dragover");
      });
    });
    stageViewport.addEventListener("drop", (e) => {
      const file = e.dataTransfer && e.dataTransfer.files && e.dataTransfer.files[0];
      if (file) uploadPhotoFile(file);
    });

    $$("#stage-toolbar .seg-btn").forEach((btn) => {
      btn.addEventListener("click", () => setStudioViewMode(btn.dataset.viewMode));
    });

    // Curtain pointer dragging + keyboard range sync
    const curtainWrap = $("#view-curtain");
    const curtainRange = $("#curtain-range");
    let draggingCurtain = false;

    const updateFromPointer = (clientX) => {
      const rect = curtainWrap.getBoundingClientRect();
      if (rect.width <= 0) return;
      const pct = ((clientX - rect.left) / rect.width) * 100;
      updateCurtainSplit(pct);
    };

    curtainWrap.addEventListener("pointerdown", (e) => {
      if (e.target === curtainRange) return;
      draggingCurtain = true;
      curtainWrap.setPointerCapture(e.pointerId);
      updateFromPointer(e.clientX);
    });
    curtainWrap.addEventListener("pointermove", (e) => {
      if (!draggingCurtain) return;
      updateFromPointer(e.clientX);
    });
    ["pointerup", "pointercancel"].forEach((evt) => {
      curtainWrap.addEventListener(evt, () => {
        draggingCurtain = false;
      });
    });
    curtainRange.addEventListener("input", (e) => {
      updateCurtainSplit(e.target.value);
    });

    $$("#studio-filter-group .choice-pill").forEach((btn) => {
      btn.addEventListener("click", () => {
        state.studio.filter = btn.dataset.filter;
        $$("#studio-filter-group .choice-pill").forEach((b) => {
          const sel = b === btn;
          b.classList.toggle("is-selected", sel);
          b.setAttribute("aria-checked", String(sel));
        });
      });
    });

    $$("#studio-mode-group .engine-option").forEach((btn) => {
      btn.addEventListener("click", () => {
        state.studio.mode = btn.dataset.mode;
        $$("#studio-mode-group .engine-option").forEach((b) => {
          const sel = b === btn;
          b.classList.toggle("is-selected", sel);
          b.setAttribute("aria-checked", String(sel));
        });
      });
    });

    $("#btn-studio-process").addEventListener("click", processStudioImage);

    // Module 03: Benchmark
    $$(".preset-card").forEach((card) => {
      card.addEventListener("click", () => selectBenchmarkPreset(card.dataset.preset));
    });
    $("#btn-bench-run").addEventListener("click", requestBenchmarkRun);

    $$("#bench-table .sort-btn").forEach((btn) => {
      btn.addEventListener("click", () => {
        const key = btn.dataset.sort;
        if (state.benchmark.sortKey === key) {
          state.benchmark.sortDir = state.benchmark.sortDir === "asc" ? "desc" : "asc";
        } else {
          state.benchmark.sortKey = key;
          state.benchmark.sortDir = key === "speedup" || key === "efficiencyPct" ? "desc" : "asc";
        }
        renderBenchmarkTable();
      });
    });

    $("#btn-export-csv").addEventListener("click", () => exportBenchmarkReport("csv"));
    $("#btn-export-txt").addEventListener("click", () => exportBenchmarkReport("txt"));

    // Module 04: Concurrency
    $$("#conc-clients-group .seg-btn").forEach((btn) => {
      btn.addEventListener("click", () => selectConcClients(btn.dataset.clients));
    });
    $("#btn-conc-run").addEventListener("click", runConcurrencyWave);

    // Logs drawer
    $("#btn-logs-toggle").addEventListener("click", () => toggleLogsDrawer());
    $("#btn-logs-close").addEventListener("click", () => toggleLogsDrawer(false));
    $("#btn-logs-refresh").addEventListener("click", () => fetchLogs(false));
    $("#logs-search").addEventListener("input", renderLogs);
    $("#btn-logs-clear").addEventListener("click", async () => {
      try {
        await apiFetch("/api/logs/clear", { method: "POST", body: "{}" });
        state.logs.items = [];
        state.logs.lastId = 0;
        renderLogs();
        refreshStatus();
        showToast("Logs limpos.", "info");
      } catch (err) {
        showToast(err.message, "error");
      }
    });
    $("#btn-logs-copy").addEventListener("click", async () => {
      const text = state.logs.items
        .map((e) => `[${e.time}] [${e.level}] [${e.source}] ${e.message}`)
        .join("\n");
      if (!text) {
        showToast("Nenhum log para copiar.", "info");
        return;
      }
      try {
        await navigator.clipboard.writeText(text);
        showToast("Logs copiados para a área de transferência.", "success");
      } catch {
        showToast("Não foi possível acessar a área de transferência.", "error");
      }
    });

    // Keyboard shortcuts (ignore inputs and open modal dialogs)
    document.addEventListener("keydown", (e) => {
      if (e.ctrlKey || e.metaKey || e.altKey) return;
      const tag = e.target && e.target.tagName ? e.target.tagName.toLowerCase() : "";
      if (tag === "input" || tag === "textarea" || tag === "select") return;
      if (document.querySelector("dialog[open]")) return;

      const key = e.key.toLowerCase();
      if (key === "1") switchModule("arch");
      else if (key === "2") switchModule("studio");
      else if (key === "3") switchModule("benchmark");
      else if (key === "4") switchModule("concurrency");
      else if (key === "f") setPresentationMode(!state.presenting);
      else if (key === "t") toggleTheme();
      else if (key === "l") toggleLogsDrawer();
    });
  }

  document.addEventListener("DOMContentLoaded", () => {
    bindEvents();
    refreshStatus();
  });
})();
