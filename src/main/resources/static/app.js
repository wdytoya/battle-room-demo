(() => {
  "use strict";

  // --- constants ---
  const HAND_LABEL = { ROCK: "石头", PAPER: "布", SCISSORS: "剪刀", NONE: "?" };
  const HAND_SHORT = { ROCK: "石", PAPER: "布", SCISSORS: "剪", NONE: "?" };
  const OUTCOME_LABEL = { WIN: "赢", LOSS: "输", DRAW: "平" };
  const RESULT_HIDE_MS = 10_000;
  const RECONCILE_SOFT = 8;
  const RECONCILE_HARD = 40;

  // --- DOM ---
  const els = {
    loginRow: document.getElementById("login-row"),
    usernameLabel: document.getElementById("username-label"),
    username: document.getElementById("username"),
    btnLogin: document.getElementById("btn-login"),
    loggedAs: document.getElementById("logged-as"),
    connStatus: document.getElementById("conn-status"),
    keyPaper: document.getElementById("key-paper"),
    keyScissors: document.getElementById("key-scissors"),
    keyRock: document.getElementById("key-rock"),
    keyLeft: document.getElementById("key-left"),
    keyRight: document.getElementById("key-right"),
    btnSaveKeys: document.getElementById("btn-save-keys"),
    keysHint: document.getElementById("keys-hint"),
    panelProfile: document.getElementById("panel-profile"),
    panelMatch: document.getElementById("panel-match"),
    panelBattle: document.getElementById("panel-battle"),
    panelLog: document.getElementById("panel-log"),
    battleTitle: document.getElementById("battle-title"),
    battlePlay: document.getElementById("battle-play"),
    battleResult: document.getElementById("battle-result"),
    battleResultActions: document.getElementById("battle-result-actions"),
    btnCloseResult: document.getElementById("btn-close-result"),
    resultAutoHint: document.getElementById("result-auto-hint"),
    profileView: document.getElementById("profile-view"),
    btnRefreshMe: document.getElementById("btn-refresh-me"),
    btnRanking: document.getElementById("btn-ranking"),
    rankingView: document.getElementById("ranking-view"),
    btnMatchStart: document.getElementById("btn-match-start"),
    btnMatchCancel: document.getElementById("btn-match-cancel"),
    matchView: document.getElementById("match-view"),
    battleMeta: document.getElementById("battle-meta"),
    countdown: document.getElementById("countdown"),
    lightsMe: document.getElementById("lights-me"),
    lightsOpp: document.getElementById("lights-opp"),
    lightsLabelMe: document.getElementById("lights-label-me"),
    lightsLabelOpp: document.getElementById("lights-label-opp"),
    arena: document.getElementById("arena"),
    collisionFlash: document.getElementById("collision-flash"),
    btnLeft: document.getElementById("btn-left"),
    btnRight: document.getElementById("btn-right"),
    log: document.getElementById("log"),
    handBtns: Array.from(document.querySelectorAll(".hand-btn")),
  };

  const ctx = els.arena.getContext("2d");

  // --- state ---
  let ws = null;
  let me = null;
  let loggedIn = false;
  let inPlaying = false;
  // Defaults: A left, D right, J paper, K scissors, L rock
  let keys = { LEFT: "a", RIGHT: "d", PAPER: "j", SCISSORS: "k", ROCK: "l" };
  let battleHideTimer = null;
  let lastThrustSent = "NONE";
  let holdLeft = false;
  let holdRight = false;

  let physics = {
    arenaWidth: 1000,
    ballRadius: 36,
    accel: 520,
    maxSpeed: 380,
    friction: 80,
    tickHz: 20,
    maxLights: 3,
    wallRestitution: 0.55,
  };
  let authTick = 0;
  let displayRemainingMs = 0;
  let lastSnapAt = 0;
  let lastCollisionTick = -1;

  let selfState = { x: 220, vx: 0, lights: 3, hand: "NONE", name: "" };
  let oppState = { x: 780, vx: 0, lights: 3, hand: "NONE", name: "" };
  /** Opponent snapshot ring for delayed interp: { tick, x, vx, t }. */
  const OPP_BUF_MAX = 8;
  const OPP_INTERP_DELAY_TICKS = 1;
  let oppSnapBuf = [];
  let oppSnapRoomId = null;
  let renderLoopId = null;
  let lastFrameTs = 0;

  // --- tiny DOM helpers ---
  function log(msg) {
    const t = new Date().toLocaleTimeString();
    els.log.textContent = `[${t}] ${msg}\n` + els.log.textContent;
  }

  function show(el) { el.classList.remove("hidden"); }
  function hide(el) { el.classList.add("hidden"); }

  // --- key bindings ---
  function normKey(raw) {
    return (raw || "").trim().toLowerCase();
  }

  function applyKeysFromInputs() {
    const left = normKey(els.keyLeft.value);
    const right = normKey(els.keyRight.value);
    const paper = normKey(els.keyPaper.value);
    const scissors = normKey(els.keyScissors.value);
    const rock = normKey(els.keyRock.value);
    const all = [left, right, paper, scissors, rock];
    if (all.some((k) => !k || k.length !== 1)) {
      els.keysHint.textContent = "每个绑定须为单个字符";
      return false;
    }
    if (new Set(all).size !== 5) {
      els.keysHint.textContent = "五个按键不能重复";
      return false;
    }
    keys = { LEFT: left, RIGHT: right, PAPER: paper, SCISSORS: scissors, ROCK: rock };
    els.keysHint.textContent =
      `当前：${left.toUpperCase()}=向左 ${right.toUpperCase()}=向右 · ` +
      `${paper.toUpperCase()}=布 ${scissors.toUpperCase()}=剪刀 ${rock.toUpperCase()}=石头`;
    return true;
  }

  // --- session / panels ---
  function clearBattleHideTimer() {
    if (battleHideTimer != null) {
      clearInterval(battleHideTimer);
      battleHideTimer = null;
    }
  }

  function setCombatControls(enabled) {
    els.handBtns.forEach((b) => { b.disabled = !enabled; });
    els.btnLeft.disabled = !enabled;
    els.btnRight.disabled = !enabled;
  }

  function setSessionUi(on, reason) {
    loggedIn = on;
    els.btnMatchStart.disabled = !on;
    els.btnMatchCancel.disabled = !on;
    els.btnRefreshMe.disabled = !on;
    els.btnRanking.disabled = !on;
    if (on) {
      els.loginRow.classList.add("logged-in");
      hide(els.usernameLabel);
      hide(els.btnLogin);
      show(els.loggedAs);
      els.loggedAs.innerHTML = `已登录 · <strong>${me || ""}</strong>`;
      show(els.panelProfile);
      show(els.panelMatch);
      show(els.panelLog);
    } else {
      els.loginRow.classList.remove("logged-in");
      show(els.usernameLabel);
      show(els.btnLogin);
      hide(els.loggedAs);
      els.loggedAs.textContent = "";
      hide(els.panelProfile);
      hide(els.panelMatch);
      hide(els.panelLog);
      hideBattlePanel(false);
      if (reason === "kicked") {
        els.btnLogin.textContent = "重新登录";
        els.connStatus.textContent = "已被踢下线，请重新登录";
      } else if (reason === "disconnect") {
        els.btnLogin.textContent = "重新登录";
      } else {
        els.btnLogin.textContent = "登录";
      }
    }
  }

  function clearOppSnapBuf() {
    oppSnapBuf = [];
    oppSnapRoomId = null;
  }

  function hideBattlePanel(restoreMatch) {
    clearBattleHideTimer();
    stopRenderLoop();
    hide(els.panelBattle);
    hide(els.battleResult);
    hide(els.battleResultActions);
    show(els.battlePlay);
    els.battleTitle.textContent = "对战";
    els.countdown.textContent = "--";
    els.battleMeta.textContent = "—";
    inPlaying = false;
    clearOppSnapBuf();
    setCombatControls(false);
    setThrust("NONE", true);
    if (restoreMatch && loggedIn) {
      show(els.panelMatch);
      els.matchView.textContent = "空闲，可再次匹配";
    }
  }

  function dismissBattleToMatch() {
    hideBattlePanel(true);
  }

  function showBattlePlaying() {
    // Idempotent enter: skip panel churn once already on the play view.
    const already =
      !els.panelBattle.classList.contains("hidden") &&
      !els.battlePlay.classList.contains("hidden") &&
      els.battleResult.classList.contains("hidden");
    if (already) {
      startRenderLoop(); // no-op if rAF already running
      return;
    }
    clearBattleHideTimer();
    hide(els.panelMatch);
    show(els.panelBattle);
    show(els.battlePlay);
    hide(els.battleResult);
    hide(els.battleResultActions);
    els.battleTitle.textContent = "对战";
    resizeArena();
    startRenderLoop();
  }

  function showBattleResultView() {
    hide(els.panelMatch);
    show(els.panelBattle);
    hide(els.battlePlay);
    show(els.battleResult);
    show(els.battleResultActions);
    els.battleTitle.textContent = "对战结果";
    els.countdown.textContent = "结算";
    inPlaying = false;
    setCombatControls(false);
    setThrust("NONE", true);
    stopRenderLoop();
    let left = Math.round(RESULT_HIDE_MS / 1000);
    els.resultAutoHint.textContent = `${left} 秒后自动关闭`;
    clearBattleHideTimer();
    battleHideTimer = setInterval(() => {
      left -= 1;
      if (left <= 0) {
        dismissBattleToMatch();
        return;
      }
      els.resultAutoHint.textContent = `${left} 秒后自动关闭`;
    }, 1000);
  }

  function handLabel(code) {
    if (!code || code === "NONE") return "?";
    return HAND_LABEL[code] || "?";
  }

  function setHandActive(hand) {
    els.handBtns.forEach((b) => {
      b.classList.toggle("active", b.dataset.hand === hand);
    });
  }

  function renderProfile(player) {
    if (!player) {
      els.profileView.textContent = "—";
      return;
    }
    if (player.display === "placement" || !player.placementComplete) {
      els.profileView.textContent =
        `${player.username} · ${player.placementLabel || ("定级中 " + player.placementProgress + "/" + player.placementGames)} · 对局 ${player.gamesPlayed}`;
    } else {
      const rankText = player.rank != null ? ` · #${player.rank}` : "";
      els.profileView.textContent =
        `${player.username} · ELO ${player.elo}${rankText} · 对局 ${player.gamesPlayed}`;
    }
  }

  function renderLights(el, count, cls) {
    const max = physics.maxLights || 3;
    el.innerHTML = "";
    for (let i = 0; i < max; i++) {
      const d = document.createElement("div");
      d.className = "light" + (i < count ? " on " + cls : "");
      el.appendChild(d);
    }
  }

  // --- network ---
  function connect() {
    if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) {
      return;
    }
    const proto = location.protocol === "https:" ? "wss:" : "ws:";
    ws = new WebSocket(`${proto}//${location.host}/ws`);
    ws.onopen = () => log("WebSocket 已连接");
    ws.onclose = () => {
      if (loggedIn) {
        els.connStatus.textContent = "已断开";
        setSessionUi(false, "disconnect");
      }
      setCombatControls(false);
      log("WebSocket 断开");
    };
    ws.onerror = () => log("WebSocket 错误");
    ws.onmessage = (ev) => onMessage(ev.data);
  }

  function send(obj) {
    if (!ws || ws.readyState !== WebSocket.OPEN) {
      log("未连接，无法发送");
      return;
    }
    ws.send(JSON.stringify(obj));
  }

  function onMessage(raw) {
    let msg;
    try { msg = JSON.parse(raw); } catch { log("非 JSON: " + raw); return; }
    switch (msg.type) {
      case "login_ok":
        me = msg.player.username;
        if (msg.physics) {
          physics = Object.assign(physics, msg.physics);
        }
        setSessionUi(true);
        els.btnLogin.textContent = "登录";
        els.connStatus.textContent = "已连接";
        renderProfile(msg.player);
        log(`登录成功: ${me} activity=${msg.activity}`);
        // Detail state comes from follow-up match_status / room_snapshot / final_result.
        if (msg.activity === "queued") {
          hideBattlePanel(false);
          show(els.panelMatch);
          els.matchView.textContent = "排队中…";
        } else if (msg.activity === "in_room") {
          hide(els.panelMatch);
        } else {
          hideBattlePanel(false);
          show(els.panelMatch);
          els.matchView.textContent = "空闲，可开始匹配";
        }
        break;
      case "match_status":
        if (msg.state === "queued") {
          hideBattlePanel(false);
          show(els.panelMatch);
          els.matchView.textContent =
            `排队中 · 等待 ${(msg.waitMs / 1000).toFixed(1)}s · 当前范围 ±${msg.range}` +
            (msg.message ? ` · ${msg.message}` : "");
        } else if (msg.state === "cancelled") {
          els.matchView.textContent = "已取消匹配";
        } else if (msg.state === "idle") {
          els.matchView.textContent = msg.message || "空闲，可再次匹配";
        } else {
          els.matchView.textContent = `${msg.state}` + (msg.message ? ` · ${msg.message}` : "");
        }
        break;
      case "room_snapshot":
        renderSnapshot(msg);
        break;
      case "final_result":
        renderFinal(msg);
        log("收到最终结算");
        break;
      case "ranking_top100": {
        const lines = (msg.entries || []).map((e) => `#${e.rank}  ${e.username}  ELO ${e.elo}`);
        els.rankingView.textContent = lines.length ? lines.join("\n") : "（暂无满 10 局玩家）";
        break;
      }
      case "player_query":
        if (msg.player && me && msg.username === me) renderProfile(msg.player);
        break;
      case "error":
        log(`错误 [${msg.code}] ${msg.message}`);
        if (msg.code === "ALREADY_IN_ROOM") els.matchView.textContent = msg.message;
        if (msg.code === "KICKED") {
          setSessionUi(false, "kicked");
          me = null;
        }
        break;
      default:
        log("未处理消息: " + msg.type);
    }
  }

  // --- snapshot / result ---
  function reconcileSelf(auth) {
    const err = Math.abs(auth.x - selfState.x);
    if (err > RECONCILE_HARD) {
      selfState.x = auth.x;
      selfState.vx = auth.vx;
      log(`回滚：自身位置误差 ${err.toFixed(1)}`);
    } else if (err > RECONCILE_SOFT) {
      selfState.x += (auth.x - selfState.x) * 0.35;
      selfState.vx += (auth.vx - selfState.vx) * 0.35;
    }
    selfState.lights = auth.lights;
    selfState.hand = auth.hand;
    if (auth.hand && auth.hand !== "NONE") {
      setHandActive(auth.hand);
    }
  }

  function applySnapshot(msg) {
    authTick = msg.tick || 0;
    if (msg.remainingMs != null) {
      const drift = Math.abs(displayRemainingMs - msg.remainingMs);
      if (drift > 250 || lastSnapAt === 0) {
        displayRemainingMs = msg.remainingMs;
      } else {
        displayRemainingMs += (msg.remainingMs - displayRemainingMs) * 0.4;
      }
    }
    lastSnapAt = performance.now();

    if (!me || !msg.players) return;
    const mine = msg.players[me];
    const oppName = msg.playerA === me ? msg.playerB : msg.playerA;
    const opp = msg.players[oppName];
    if (mine) {
      reconcileSelf(mine);
      selfState.name = me;
    }
    if (opp) {
      pushOppSnap(authTick, opp.x, opp.vx);
      if (oppSnapBuf.length === 1 || Math.abs(oppState.x - opp.x) > 200) {
        oppState.x = opp.x;
        oppState.vx = opp.vx;
      }
      oppState.lights = opp.lights;
      oppState.hand = opp.hand;
      oppState.name = oppName;
    }

    els.lightsLabelMe.textContent = me + (mine && mine.side === "A" ? "（左）" : mine && mine.side === "B" ? "（右）" : "");
    els.lightsLabelOpp.textContent = (oppName || "对手") + (opp && opp.side === "A" ? "（左）" : opp && opp.side === "B" ? "（右）" : "");
    renderLights(els.lightsMe, selfState.lights, "me");
    renderLights(els.lightsOpp, oppState.lights, "opp");

    els.battleMeta.textContent =
      `房间 ${msg.roomId || "-"} · tick ${authTick} · ${msg.playerA}(左) vs ${msg.playerB}(右)`;

    if (msg.lastCollision && msg.lastCollision.tick !== lastCollisionTick) {
      lastCollisionTick = msg.lastCollision.tick;
      const c = msg.lastCollision;
      let text = "对撞！";
      if (c.winner) {
        text += ` ${c.winner} 胜（${handLabel(c.handA)} vs ${handLabel(c.handB)}）`;
      } else {
        text += ` 平（${handLabel(c.handA)} vs ${handLabel(c.handB)}，无扣灯）`;
      }
      els.collisionFlash.textContent = text;
      show(els.collisionFlash);
      setTimeout(() => hide(els.collisionFlash), 1200);
    }
  }

  function renderSnapshot(msg) {
    if (msg.phase === "RESULT") return;
    if (msg.roomId != null && msg.roomId !== oppSnapRoomId) {
      oppSnapBuf = [];
      oppSnapRoomId = msg.roomId;
    }
    showBattlePlaying();
    inPlaying = msg.phase === "PLAYING";
    setCombatControls(inPlaying && loggedIn);
    applySnapshot(msg);
    if (inPlaying) {
      els.countdown.textContent = (displayRemainingMs / 1000).toFixed(1) + "s";
    }
  }

  function renderFinal(msg) {
    applySnapshot(msg);
    inPlaying = false;
    clearOppSnapBuf();
    setCombatControls(false);
    setThrust("NONE", true);

    const by = msg.result && msg.result.byUsername;
    if (!by || !me || !by[me]) {
      els.battleResult.textContent = "收到最终结果，但缺少本人字段";
      showBattleResultView();
      return;
    }
    const mine = by[me];
    const oppName = msg.playerA === me ? msg.playerB : msg.playerA;
    const opp = by[oppName];
    const cls =
      mine.outcome === "WIN" ? "outcome-win" :
      mine.outcome === "LOSS" ? "outcome-loss" : "outcome-draw";
    const endReason = (msg.result && msg.result.endReason) || "TIME";
    let html = `<div><span class="${cls}">自己：${OUTCOME_LABEL[mine.outcome] || mine.outcome}</span></div>`;
    if (endReason === "LIGHTS") {
      if (mine.outcome === "WIN") {
        html += `<div class="muted" style="margin-top:6px">对手血量耗尽</div>`;
      } else if (mine.outcome === "LOSS") {
        html += `<div class="muted" style="margin-top:6px">己方血量耗尽</div>`;
      } else {
        html += `<div class="muted" style="margin-top:6px">双方血量同时耗尽</div>`;
      }
    } else {
      const myL = mine.lights != null ? mine.lights : "?";
      const oppL = opp && opp.lights != null ? opp.lights : "?";
      html += `<div class="muted" style="margin-top:6px">倒计时结束 · 血量 ${myL} : ${oppL}</div>`;
    }
    if (mine.display === "elo") {
      const delta = mine.eloDelta >= 0 ? "+" + mine.eloDelta : String(mine.eloDelta);
      html += `<div class="muted" style="margin-top:6px">ELO ${mine.elo} (${delta})`;
      if (mine.rank != null) html += ` · 排名 #${mine.rank}`;
      html += "</div>";
    } else {
      html += `<div class="muted" style="margin-top:6px">${mine.placementLabel || "定级中"} · 对局 ${mine.gamesPlayed}</div>`;
    }
    els.battleResult.innerHTML = html;
    showBattleResultView();

    if (mine.display === "elo") {
      renderProfile({
        username: me, elo: mine.elo, rank: mine.rank,
        gamesPlayed: mine.gamesPlayed, placementComplete: true, display: "elo",
      });
    } else {
      renderProfile({
        username: me, gamesPlayed: mine.gamesPlayed, placementComplete: false,
        placementProgress: Math.min(mine.gamesPlayed, 10), placementGames: 10,
        display: "placement", placementLabel: mine.placementLabel,
      });
    }
  }

  // --- combat input ---
  function currentThrust() {
    if (holdLeft && !holdRight) return "LEFT";
    if (holdRight && !holdLeft) return "RIGHT";
    return "NONE";
  }

  function setThrust(thrust, forceSend) {
    if (!forceSend && thrust === lastThrustSent) return;
    lastThrustSent = thrust;
    els.btnLeft.classList.toggle("active", thrust === "LEFT");
    els.btnRight.classList.toggle("active", thrust === "RIGHT");
    if (inPlaying && loggedIn) {
      send({ type: "input_thrust", thrust });
    }
  }

  function syncThrustFromHolds() {
    setThrust(currentThrust(), false);
  }

  function bindHold(btn, setter) {
    const down = (e) => {
      e.preventDefault();
      if (!inPlaying) return;
      setter(true);
      syncThrustFromHolds();
    };
    const up = (e) => {
      e.preventDefault();
      setter(false);
      syncThrustFromHolds();
    };
    btn.addEventListener("mousedown", down);
    btn.addEventListener("mouseup", up);
    btn.addEventListener("mouseleave", up);
    btn.addEventListener("touchstart", down, { passive: false });
    btn.addEventListener("touchend", up);
    btn.addEventListener("touchcancel", up);
  }

  // --- prediction / draw / render loop ---
  function predictSelf(dt) {
    let ax = 0;
    const thrust = currentThrust();
    if (thrust === "RIGHT") ax = physics.accel;
    else if (thrust === "LEFT") ax = -physics.accel;
    else {
      if (selfState.vx > 0) ax = -physics.friction;
      else if (selfState.vx < 0) ax = physics.friction;
    }
    let vx = selfState.vx + ax * dt;
    if (thrust === "NONE") {
      if (selfState.vx > 0 && vx < 0) vx = 0;
      if (selfState.vx < 0 && vx > 0) vx = 0;
    }
    vx = Math.max(-physics.maxSpeed, Math.min(physics.maxSpeed, vx));
    let x = selfState.x + vx * dt;
    const min = physics.ballRadius;
    const max = physics.arenaWidth - physics.ballRadius;
    if (x < min) {
      x = min;
      vx = Math.abs(vx) * physics.wallRestitution;
    } else if (x > max) {
      x = max;
      vx = -Math.abs(vx) * physics.wallRestitution;
    }
    selfState.vx = vx;
    selfState.x = x;
  }

  function pushOppSnap(tick, x, vx) {
    const t = performance.now();
    const last = oppSnapBuf[oppSnapBuf.length - 1];
    if (last && tick < last.tick) {
      return; // stale / out-of-order; buf is cleared on match boundaries
    }
    if (last && tick === last.tick) {
      last.x = x;
      last.vx = vx;
      last.t = t;
      return;
    }
    oppSnapBuf.push({ tick, x, vx, t });
    while (oppSnapBuf.length > OPP_BUF_MAX) oppSnapBuf.shift();
  }

  /**
   * Classic entity interp: render ~1 snapshot period behind latest,
   * lerp between the two buffered snaps that bracket that time.
   */
  function interpolateOpp() {
    if (oppSnapBuf.length === 0) return;
    if (oppSnapBuf.length === 1) {
      oppState.x = oppSnapBuf[0].x;
      oppState.vx = oppSnapBuf[0].vx;
      return;
    }
    const periodMs = 1000 / Math.max(1, physics.tickHz);
    const renderAt = performance.now() - OPP_INTERP_DELAY_TICKS * periodMs;

    let i = 0;
    while (i < oppSnapBuf.length - 1 && oppSnapBuf[i + 1].t <= renderAt) i++;
    const a = oppSnapBuf[i];
    const b = oppSnapBuf[Math.min(i + 1, oppSnapBuf.length - 1)];
    if (b.t <= a.t || renderAt <= a.t) {
      oppState.x = a.x;
      oppState.vx = a.vx;
      return;
    }
    if (renderAt >= b.t) {
      oppState.x = b.x;
      oppState.vx = b.vx;
      return;
    }
    const u = (renderAt - a.t) / (b.t - a.t);
    oppState.x = a.x + (b.x - a.x) * u;
    oppState.vx = a.vx + (b.vx - a.vx) * u;
  }

  /** Keep canvas bitmap size = CSS size so physics radius matches drawn circle. */
  function resizeArena() {
    const cssW = els.arena.clientWidth || 800;
    const cssH = 220;
    const dpr = window.devicePixelRatio || 1;
    const bw = Math.max(1, Math.round(cssW * dpr));
    const bh = Math.max(1, Math.round(cssH * dpr));
    if (els.arena.width !== bw || els.arena.height !== bh) {
      els.arena.width = bw;
      els.arena.height = bh;
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    }
    return { w: cssW, h: cssH };
  }

  function drawArena() {
    const { w, h } = resizeArena();
    ctx.clearRect(0, 0, w, h);
    ctx.fillStyle = "#1c242c";
    ctx.fillRect(0, h * 0.62, w, h * 0.38);
    ctx.strokeStyle = "#3a4550";
    ctx.beginPath();
    ctx.moveTo(0, h * 0.62);
    ctx.lineTo(w, h * 0.62);
    ctx.stroke();

    const scale = w / physics.arenaWidth;
    const y = h * 0.62;
    const r = physics.ballRadius * scale;

    function ball(xWorld, color, hand, label) {
      const x = xWorld * scale;
      const cy = y - r;
      ctx.beginPath();
      ctx.arc(x, cy, r, 0, Math.PI * 2);
      ctx.fillStyle = color;
      ctx.fill();
      ctx.strokeStyle = "#0f1419";
      ctx.lineWidth = 2;
      ctx.stroke();

      // Gesture inside the ball (same radius = collision visual)
      ctx.fillStyle = "#0b1016";
      ctx.font = `bold ${Math.max(12, Math.floor(r * 0.7))}px sans-serif`;
      ctx.textAlign = "center";
      ctx.textBaseline = "middle";
      ctx.fillText(HAND_SHORT[hand] || "?", x, cy);

      ctx.fillStyle = "#e8eef4";
      ctx.font = "11px sans-serif";
      ctx.textBaseline = "alphabetic";
      ctx.fillText(label || "", x, cy - r - 6);
    }

    ball(selfState.x, "#5b9fd4", selfState.hand, "我");
    ball(oppState.x, "#e07a5f", oppState.hand, "敌");
  }

  function stopRenderLoop() {
    if (renderLoopId != null) {
      cancelAnimationFrame(renderLoopId);
      renderLoopId = null;
    }
  }

  function startRenderLoop() {
    if (renderLoopId != null) return;
    lastFrameTs = performance.now();
    const loop = (ts) => {
      renderLoopId = requestAnimationFrame(loop);
      const dt = Math.min(0.05, (ts - lastFrameTs) / 1000);
      lastFrameTs = ts;
      if (inPlaying) {
        predictSelf(dt);
        interpolateOpp();
        displayRemainingMs = Math.max(0, displayRemainingMs - dt * 1000);
        els.countdown.textContent = (displayRemainingMs / 1000).toFixed(1) + "s";
      }
      drawArena();
    };
    renderLoopId = requestAnimationFrame(loop);
  }

  // --- event wiring ---
  els.btnLogin.addEventListener("click", () => {
    applyKeysFromInputs();
    connect();
    const name = (els.username.value || "").trim();
    if (!name) {
      els.connStatus.textContent = "请输入用户名";
      return;
    }
    const tryLogin = () => send({ type: "login", username: name });
    if (ws.readyState === WebSocket.OPEN) tryLogin();
    else ws.addEventListener("open", tryLogin, { once: true });
  });

  els.btnSaveKeys.addEventListener("click", () => applyKeysFromInputs());
  els.btnMatchStart.addEventListener("click", () => send({ type: "match_start" }));
  els.btnMatchCancel.addEventListener("click", () => send({ type: "match_cancel" }));
  els.btnRefreshMe.addEventListener("click", () => send({ type: "query_me" }));
  els.btnRanking.addEventListener("click", () => send({ type: "query_ranking_top100" }));
  els.btnCloseResult.addEventListener("click", () => dismissBattleToMatch());

  els.handBtns.forEach((btn) => {
    btn.addEventListener("click", () => {
      if (!inPlaying) return;
      send({ type: "input_hand", hand: btn.dataset.hand });
      selfState.hand = btn.dataset.hand;
      setHandActive(btn.dataset.hand);
    });
  });

  bindHold(els.btnLeft, (v) => { holdLeft = v; });
  bindHold(els.btnRight, (v) => { holdRight = v; });

  document.addEventListener("keydown", (ev) => {
    if (!inPlaying || !loggedIn) return;
    const tag = (ev.target && ev.target.tagName) || "";
    if (tag === "INPUT" || tag === "TEXTAREA") return;
    const k = ev.key.length === 1 ? ev.key.toLowerCase() : "";
    if (!k) return;
    if (k === keys.LEFT) {
      holdLeft = true; syncThrustFromHolds(); ev.preventDefault(); return;
    }
    if (k === keys.RIGHT) {
      holdRight = true; syncThrustFromHolds(); ev.preventDefault(); return;
    }
    let hand = null;
    if (k === keys.ROCK) hand = "ROCK";
    else if (k === keys.PAPER) hand = "PAPER";
    else if (k === keys.SCISSORS) hand = "SCISSORS";
    if (hand) {
      ev.preventDefault();
      send({ type: "input_hand", hand });
      selfState.hand = hand;
      setHandActive(hand);
    }
  });

  document.addEventListener("keyup", (ev) => {
    const k = ev.key.length === 1 ? ev.key.toLowerCase() : "";
    if (k === keys.LEFT) {
      holdLeft = false; syncThrustFromHolds();
    }
    if (k === keys.RIGHT) {
      holdRight = false; syncThrustFromHolds();
    }
  });

  window.addEventListener("resize", () => {
    if (inPlaying || !els.panelBattle.classList.contains("hidden")) {
      resizeArena();
      drawArena();
    }
  });

  // --- boot ---
  applyKeysFromInputs();
  resizeArena();
  drawArena();
})();
