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
    btnTogglePing: document.getElementById("btn-toggle-ping"),
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
    btnMatch: document.getElementById("btn-match"),
    matchView: document.getElementById("match-view"),
    battleMeta: document.getElementById("battle-meta"),
    battleTip: document.getElementById("battle-tip"),
    countdown: document.getElementById("countdown"),
    lightsLeft: document.getElementById("lights-left"),
    lightsRight: document.getElementById("lights-right"),
    lightsLabelLeft: document.getElementById("lights-label-left"),
    lightsLabelRight: document.getElementById("lights-label-right"),
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
  let sessionToken = null;
  let reconnectToken = null;
  let lastAckSeq = 0;
  let heartbeatTimer = null;
  /** Filled from login_ok / reconnect_ok (ClusterConfig.HEARTBEAT_CLIENT_INTERVAL_MS). */
  let heartbeatIntervalMs = 5000;
  let reconnectTimer = null;
  let reconnectAttempts = 0;
  let suppressAutoReconnect = false;
  let pingEnabled = true;
  let matching = false;
  let battleRoomId = null;
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

  /** True only while WS is open — soft-disconnect keeps loggedIn for reconnect UI. */
  function linkUp() {
    return !!(ws && ws.readyState === WebSocket.OPEN);
  }

  function canCombat() {
    return inPlaying && loggedIn && linkUp();
  }

  const BATTLE_TIP_DEFAULT =
    "左右为场地绝对方向；先点「开始匹配」的一方出生在左。手势在球内；对撞猜拳扣灯。";

  function showDisconnectTip() {
    if (!els.battleTip) return;
    // Keep countdown as match clock; tip under controls is the reconnect cue.
    if (els.panelBattle.classList.contains("hidden")) return;
    els.battleTip.textContent = "与服务器断开连接，正在重连中......";
    els.battleTip.classList.remove("muted");
    els.battleTip.classList.add("tip-reconnect");
  }

  function restoreBattleTip() {
    if (!els.battleTip) return;
    els.battleTip.textContent = BATTLE_TIP_DEFAULT;
    els.battleTip.classList.add("muted");
    els.battleTip.classList.remove("tip-reconnect");
  }

  /** Freeze local prediction/input on disconnect; authority resumes after reconnect. */
  function freezeCombatLocal() {
    holdLeft = false;
    holdRight = false;
    lastThrustSent = "NONE";
    els.btnLeft.classList.remove("active");
    els.btnRight.classList.remove("active");
    setCombatControls(false);
    // Stop rAF: keep last canvas frame; no fake countdown/interp while disconnected.
    stopRenderLoop();
  }

  function setMatchButton(queued) {
    matching = !!queued;
    if (!els.btnMatch) return;
    els.btnMatch.textContent = matching ? "取消匹配" : "开始匹配";
  }

  function clearHandSelection() {
    selfState.hand = "NONE";
    els.handBtns.forEach((b) => b.classList.remove("active"));
  }

  function setSessionUi(on, reason) {
    loggedIn = on;
    els.btnMatch.disabled = !on;
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
      setMatchButton(false);
      if (reason === "kicked") {
        els.btnLogin.textContent = "重新登录";
        els.connStatus.textContent = "由于长时间无动作，已断开连接";
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
    battleRoomId = null;
    clearOppSnapBuf();
    clearHandSelection();
    setCombatControls(false);
    setThrust("NONE", true);
    if (restoreMatch && loggedIn) {
      show(els.panelMatch);
      setMatchButton(false);
      els.matchView.textContent = "空闲，可再次匹配";
    }
  }

  function dismissBattleToMatch() {
    hideBattlePanel(true);
  }

  function showBattlePlaying(isNewRoom) {
    // Idempotent enter: skip panel churn once already on the play view.
    const already =
      !els.panelBattle.classList.contains("hidden") &&
      !els.battlePlay.classList.contains("hidden") &&
      els.battleResult.classList.contains("hidden");
    if (isNewRoom) {
      clearHandSelection();
      setMatchButton(false);
    }
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
    clearHandSelection();
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
    if (!hand || hand === "NONE") {
      clearHandSelection();
      return;
    }
    selfState.hand = hand;
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
  function stopHeartbeat() {
    if (heartbeatTimer) {
      clearInterval(heartbeatTimer);
      heartbeatTimer = null;
    }
  }

  function startHeartbeat() {
    stopHeartbeat();
    if (!pingEnabled) return;
    const interval = heartbeatIntervalMs > 0 ? heartbeatIntervalMs : 5000;
    heartbeatTimer = setInterval(() => {
      send({ type: "ping", t: Date.now() });
    }, interval);
  }

  function updatePingToggleUi() {
    if (!els.btnTogglePing) return;
    els.btnTogglePing.textContent = pingEnabled ? "心跳ping:开" : "心跳ping:关";
    els.btnTogglePing.classList.toggle("off", !pingEnabled);
  }

  function scheduleReconnect() {
    if (suppressAutoReconnect || !reconnectToken || !me) return;
    if (reconnectTimer) return;
    const delay = Math.min(8000, 500 + reconnectAttempts * 500);
    reconnectAttempts += 1;
    els.connStatus.textContent = "重连中…";
    log(`将在 ${delay}ms 后尝试重连 (#${reconnectAttempts})`);
    reconnectTimer = setTimeout(() => {
      reconnectTimer = null;
      connect(true);
    }, delay);
  }

  function connect(isReconnect) {
    if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) {
      return;
    }
    const proto = location.protocol === "https:" ? "wss:" : "ws:";
    ws = new WebSocket(`${proto}//${location.host}/ws`);
    ws.onopen = () => {
      log("WebSocket 已连接");
      // Always resume with reconnect when we still hold a token (not only when isReconnect flag is set).
      if (me && reconnectToken) {
        send({
          type: "reconnect",
          username: me,
          reconnectToken,
          lastAckSeq,
        });
      } else if (isReconnect) {
        log("重连缺少凭证，请重新登录");
      }
    };
    ws.onclose = () => {
      stopHeartbeat();
      freezeCombatLocal();
      log("WebSocket 断开");
      if (loggedIn && !suppressAutoReconnect && reconnectToken) {
        els.connStatus.textContent = "已断开";
        showDisconnectTip();
        scheduleReconnect();
      } else if (loggedIn) {
        els.connStatus.textContent = "已断开";
        restoreBattleTip();
        setSessionUi(false, "disconnect");
      } else {
        restoreBattleTip();
      }
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

  /** Drop the socket after auth failure so Gateway idle-kick cannot overwrite UI later. */
  function closeSocketQuiet() {
    if (!ws) return;
    const sock = ws;
    ws = null;
    try {
      sock.onopen = null;
      sock.onclose = null;
      sock.onmessage = null;
      sock.onerror = null;
      sock.close();
    } catch (_) { /* ignore */ }
  }

  function applyAuthOk(msg, label) {
    me = msg.player.username;
    if (msg.sessionToken) sessionToken = msg.sessionToken;
    if (msg.reconnectToken) reconnectToken = msg.reconnectToken;
    if (typeof msg.seq === "number") lastAckSeq = Math.max(lastAckSeq, msg.seq);
    if (typeof msg.serverSeq === "number") lastAckSeq = Math.max(lastAckSeq, msg.serverSeq);
    if (msg.physics) physics = Object.assign(physics, msg.physics);
    if (typeof msg.heartbeatIntervalMs === "number" && msg.heartbeatIntervalMs > 0) {
      heartbeatIntervalMs = msg.heartbeatIntervalMs;
    }
    suppressAutoReconnect = false;
    reconnectAttempts = 0;
    setSessionUi(true);
    restoreBattleTip();
    els.btnLogin.textContent = "登录";
    els.connStatus.textContent = "已连接";
    renderProfile(msg.player);
    startHeartbeat();
    log(`${label}: ${me} activity=${msg.activity} heartbeatIntervalMs=${heartbeatIntervalMs}`);
    if (msg.activity === "queued") {
      hideBattlePanel(false);
      show(els.panelMatch);
      setMatchButton(true);
      els.matchView.textContent = "排队中…";
    } else if (msg.activity === "in_room") {
      hide(els.panelMatch);
      setMatchButton(false);
    } else {
      hideBattlePanel(false);
      show(els.panelMatch);
      setMatchButton(false);
      els.matchView.textContent = "空闲，可开始匹配";
    }
  }

  function onMessage(raw) {
    let msg;
    try { msg = JSON.parse(raw); } catch { log("非 JSON: " + raw); return; }
    if (typeof msg.seq === "number") lastAckSeq = Math.max(lastAckSeq, msg.seq);
    switch (msg.type) {
      case "login_ok":
        applyAuthOk(msg, "登录成功");
        break;
      case "reconnect_ok":
        applyAuthOk(msg, "重连成功");
        break;
      case "pong":
        break;
      case "kick":
        // Already logged out (e.g. BAD_RECONNECT_TOKEN left the WS open until idle kick):
        // do not replace "重连失败" with heartbeat-kick copy.
        if (!loggedIn) {
          closeSocketQuiet();
          log(`忽略踢人（已退出登录） reason=${msg.reason || "?"}`);
          break;
        }
        suppressAutoReconnect = true;
        reconnectToken = null;
        sessionToken = null;
        stopHeartbeat();
        if (reconnectTimer) {
          clearTimeout(reconnectTimer);
          reconnectTimer = null;
        }
        restoreBattleTip();
        setSessionUi(false, "kicked");
        me = null;
        els.connStatus.textContent = "由于长时间无动作，已断开连接";
        closeSocketQuiet();
        log(`断开连接 reason=${msg.reason || "?"}`);
        break;
      case "match_status":
        // After kick/logout, ignore late match frames (race with channel close).
        if (!loggedIn) break;
        if (msg.state === "queued") {
          hideBattlePanel(false);
          show(els.panelMatch);
          setMatchButton(true);
          els.matchView.textContent =
            `排队中 · 等待 ${(msg.waitMs / 1000).toFixed(1)}s · 当前范围 ±${msg.range}` +
            (msg.message ? ` · ${msg.message}` : "");
        } else if (msg.state === "cancelled") {
          setMatchButton(false);
          els.matchView.textContent = "已取消匹配";
        } else if (msg.state === "idle") {
          setMatchButton(false);
          els.matchView.textContent = msg.message || "空闲，可再次匹配";
        } else {
          els.matchView.textContent = `${msg.state}` + (msg.message ? ` · ${msg.message}` : "");
        }
        break;
      case "room_snapshot":
        // Kick hides battle; a concurrent room_snapshot can arrive after kick on the same
        // socket (Gateway heartbeat close vs Logic fanout). Without this gate the battle
        // panel reappears while the login UI is already shown — rare / hard to reproduce.
        if (!loggedIn) break;
        renderSnapshot(msg);
        break;
      case "final_result":
        if (!loggedIn) break;
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
        if (msg.code === "KICKED"
            || msg.code === "BAD_RECONNECT_TOKEN"
            || msg.code === "SESSION_GONE") {
          // All three end in "must login again": clear tokens, stop auto-reconnect, quiet-close.
          // (KICKED used to skip sessionToken / closeSocketQuiet and rely on server close — incomplete.)
          const kicked = msg.code === "KICKED";
          suppressAutoReconnect = true;
          reconnectToken = null;
          sessionToken = null;
          stopHeartbeat();
          if (reconnectTimer) {
            clearTimeout(reconnectTimer);
            reconnectTimer = null;
          }
          restoreBattleTip();
          setSessionUi(false, kicked ? "kicked" : "disconnect");
          me = null;
          if (!kicked) {
            els.connStatus.textContent = "重连失败，请重新登录";
            log("重连凭证无效或会话已失效，请重新登录");
          }
          closeSocketQuiet();
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
    setHandActive(auth.hand);
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

    // HUD left/right = arena side A/B (fixed); 我/敌 mark identity.
    const iAmLeft = !mine || mine.side !== "B";
    if (iAmLeft) {
      els.lightsLabelLeft.textContent = "我 · " + (me || "");
      els.lightsLabelRight.textContent = "敌 · " + (oppName || "对手");
      renderLights(els.lightsLeft, selfState.lights, "me");
      renderLights(els.lightsRight, oppState.lights, "opp");
    } else {
      els.lightsLabelLeft.textContent = "敌 · " + (oppName || "对手");
      els.lightsLabelRight.textContent = "我 · " + (me || "");
      renderLights(els.lightsLeft, oppState.lights, "opp");
      renderLights(els.lightsRight, selfState.lights, "me");
    }

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
    const isNewRoom = msg.roomId != null && msg.roomId !== battleRoomId;
    if (msg.roomId != null && msg.roomId !== oppSnapRoomId) {
      oppSnapBuf = [];
      oppSnapRoomId = msg.roomId;
    }
    if (isNewRoom) {
      battleRoomId = msg.roomId;
    }
    showBattlePlaying(isNewRoom);
    inPlaying = msg.phase === "PLAYING";
    setCombatControls(canCombat());
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
    if (canCombat()) {
      send({ type: "input_thrust", thrust });
    }
  }

  function syncThrustFromHolds() {
    if (!canCombat()) return;
    setThrust(currentThrust(), false);
  }

  function bindHold(btn, setter) {
    const down = (e) => {
      e.preventDefault();
      if (!canCombat()) return;
      setter(true);
      syncThrustFromHolds();
    };
    const up = (e) => {
      e.preventDefault();
      if (!linkUp()) {
        holdLeft = false;
        holdRight = false;
        return;
      }
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
    ball(oppState.x, "#e07a5f", oppState.hand, "");
  }

  function stopRenderLoop() {
    if (renderLoopId != null) {
      cancelAnimationFrame(renderLoopId);
      renderLoopId = null;
    }
  }

  function startRenderLoop() {
    if (renderLoopId != null) return;
    if (!linkUp()) return;
    lastFrameTs = performance.now();
    const loop = (ts) => {
      if (!linkUp()) {
        renderLoopId = null;
        return;
      }
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
  els.btnMatch.addEventListener("click", () => {
    if (matching) send({ type: "match_cancel" });
    else send({ type: "match_start" });
  });
  els.btnTogglePing.addEventListener("click", () => {
    pingEnabled = !pingEnabled;
    updatePingToggleUi();
    if (pingEnabled && loggedIn && ws && ws.readyState === WebSocket.OPEN) {
      startHeartbeat();
      log("已开启客户端心跳 ping");
    } else {
      stopHeartbeat();
      log("已关闭客户端心跳 ping（可用于验证服务端超时踢人）");
    }
  });
  els.btnRefreshMe.addEventListener("click", () => send({ type: "query_me" }));
  els.btnRanking.addEventListener("click", () => send({ type: "query_ranking_top100" }));
  els.btnCloseResult.addEventListener("click", () => dismissBattleToMatch());

  els.handBtns.forEach((btn) => {
    btn.addEventListener("click", () => {
      if (!canCombat()) return;
      send({ type: "input_hand", hand: btn.dataset.hand });
      selfState.hand = btn.dataset.hand;
      setHandActive(btn.dataset.hand);
    });
  });

  bindHold(els.btnLeft, (v) => { holdLeft = v; });
  bindHold(els.btnRight, (v) => { holdRight = v; });

  document.addEventListener("keydown", (ev) => {
    if (!canCombat()) return;
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
    if (!linkUp()) {
      holdLeft = false;
      holdRight = false;
      return;
    }
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
  updatePingToggleUi();
  setMatchButton(false);
  resizeArena();
  drawArena();
})();
