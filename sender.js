/* WiFi TV Sender - captures the screen/tab with getDisplayMedia() and streams it to the phone via WebRTC.
 * Signaling goes through the local Python server (same origin, /ws). Media is peer-to-peer. */
(() => {
  'use strict';
  const $ = (id) => document.getElementById(id);
  const state = {
    ws: null, pc: null, stream: null, casting: false, code: '',
    cfg: { height: 720, fps: 30 }, retryTimer: null, retries: 0, discTimer: null, pending: [],
  };

  function setStatus(text, kind) {
    const el = $('status');
    el.textContent = text;
    el.className = 'pill ' + (kind || 'idle');
    log(text);
  }
  function log(msg) {
    const el = $('log');
    el.textContent += new Date().toLocaleTimeString() + '  ' + msg + '\n';
    const lines = el.textContent.split('\n');
    if (lines.length > 80) el.textContent = lines.slice(-80).join('\n');
    el.scrollTop = el.scrollHeight;
  }
  function updateButtons() {
    $('startBtn').disabled = state.casting;
    $('stopBtn').disabled = !state.casting;
    $('code').disabled = state.casting;
  }
  function deviceName() {
    const ua = navigator.userAgent;
    const browser = /Edg\//.test(ua) ? 'Edge' : /Chrome\//.test(ua) ? 'Chrome' : /Firefox\//.test(ua) ? 'Firefox' : 'Browser';
    const platform = (navigator.userAgentData && navigator.userAgentData.platform) || navigator.platform || 'unknown OS';
    return browser + ' on ' + platform;
  }
  function send(obj) {
    if (state.ws && state.ws.readyState === WebSocket.OPEN) state.ws.send(JSON.stringify(obj));
  }

  async function start() {
    if (state.casting) return;
    const code = $('code').value.trim();
    if (!/^\d{6}$/.test(code)) {
      setStatus('Enter the 6-digit pairing code shown on the phone', 'error');
      $('code').focus();
      return;
    }
    if (!navigator.mediaDevices || !navigator.mediaDevices.getDisplayMedia) {
      setStatus('Screen capture is unavailable. Open this page at http://localhost:' + location.port + '/ in Chrome or Edge.', 'error');
      return;
    }
    try { localStorage.setItem('wifitv.code', code); } catch (e) { /* ignore */ }
    try {
      // Must run directly inside the click handler (user gesture).
      state.stream = await navigator.mediaDevices.getDisplayMedia({
        video: { frameRate: { ideal: 30 } },
        audio: { echoCancellation: false, noiseSuppression: false, autoGainControl: false },
      });
    } catch (e) {
      if (e && e.name === 'NotAllowedError') setStatus('Screen sharing was cancelled', 'idle');
      else setStatus('Could not capture the screen: ' + (e && e.message ? e.message : e), 'error');
      return;
    }
    state.casting = true;
    state.code = code;
    state.retries = 0;
    state.stream.getVideoTracks()[0].addEventListener('ended', () => stop('Screen sharing ended', 'idle'));
    $('preview').srcObject = state.stream;
    $('audioNote').textContent = state.stream.getAudioTracks().length
      ? 'Audio is being shared.'
      : 'No audio is being shared. See "Sharing audio" below: tick "Also share tab/system audio" in the picker.';
    updateButtons();
    connectWs();
  }

  function connectWs() {
    clearTimeout(state.retryTimer);
    if (!state.casting) return;
    const proto = location.protocol === 'https:' ? 'wss' : 'ws';
    const url = proto + '://' + location.host + '/ws?role=sender&code=' + state.code +
      '&name=' + encodeURIComponent(deviceName());
    setStatus(state.retries ? 'Reconnecting to the server…' : 'Connecting…', 'busy');
    const ws = new WebSocket(url);
    state.ws = ws;
    ws.onopen = () => { state.retries = 0; setStatus('Connected to the server. Waiting for the phone…', 'busy'); };
    ws.onmessage = (ev) => {
      if (ws !== state.ws) return;
      let m;
      try { m = JSON.parse(ev.data); } catch (e) { return; }
      onSignal(m);
    };
    ws.onclose = () => {
      if (ws !== state.ws || !state.casting) return;
      closePeer();
      state.retries++;
      setStatus('Lost the connection to the server. Retrying…', 'busy');
      state.retryTimer = setTimeout(connectWs, Math.min(8000, 1000 * state.retries));
    };
    ws.onerror = () => { /* onclose follows */ };
  }

  async function onSignal(m) {
    switch (m.type) {
      case 'receiver-ready':
      case 'need-offer':
        setStatus('Phone found. Starting the stream…', 'busy');
        await startPeer();
        break;
      case 'answer':
        if (state.pc && state.pc.signalingState === 'have-local-offer') {
          await state.pc.setRemoteDescription({ type: 'answer', sdp: m.sdp });
          for (const c of state.pending.splice(0)) await addCandidate(c);
        }
        break;
      case 'candidate':
        if (state.pc && state.pc.remoteDescription) await addCandidate(m);
        else state.pending.push(m);
        break;
      case 'config':
        state.cfg = { height: m.height || 720, fps: m.fps || 30 };
        log('Phone requested ' + state.cfg.height + 'p @ ' + state.cfg.fps + ' fps');
        await applyConfig();
        break;
      case 'receiver-left':
        closePeer();
        setStatus('The phone disconnected. Waiting for it to come back…', 'busy');
        break;
      case 'bye':
        stop('The phone ended the session', 'idle');
        break;
      case 'error':
        stop(m.message || 'Server error', 'error');
        break;
    }
  }

  async function addCandidate(c) {
    try {
      await state.pc.addIceCandidate({ candidate: c.candidate, sdpMid: c.sdpMid, sdpMLineIndex: c.sdpMLineIndex });
    } catch (e) { log('ICE candidate ignored: ' + e.message); }
  }

  async function startPeer() {
    if (!state.casting || !state.stream) return;
    closePeer();
    // No ICE servers: same-Wi-Fi operation uses host candidates only (no internet needed).
    const pc = new RTCPeerConnection({ iceServers: [] });
    state.pc = pc;
    state.stream.getTracks().forEach((t) => pc.addTrack(t, state.stream));
    pc.onicecandidate = (e) => {
      if (e.candidate) send({ type: 'candidate', candidate: e.candidate.candidate,
                              sdpMid: e.candidate.sdpMid, sdpMLineIndex: e.candidate.sdpMLineIndex });
    };
    pc.onconnectionstatechange = () => {
      if (pc !== state.pc) return;
      const s = pc.connectionState;
      clearTimeout(state.discTimer);
      if (s === 'connected') {
        setStatus('Casting to the phone', 'ok');
        applyConfig();
      } else if (s === 'disconnected') {
        setStatus('Connection interrupted. Trying to recover…', 'busy');
        state.discTimer = setTimeout(() => {
          if (state.casting && pc === state.pc && pc.connectionState !== 'connected') startPeer();
        }, 8000);
      } else if (s === 'failed') {
        setStatus('Connection failed. Re-establishing…', 'busy');
        setTimeout(() => { if (state.casting && pc === state.pc) startPeer(); }, 1000);
      }
    };
    try {
      const offer = await pc.createOffer();
      await pc.setLocalDescription(offer);
      send({ type: 'offer', sdp: pc.localDescription.sdp });
    } catch (e) {
      setStatus('Could not start the stream: ' + e.message, 'error');
    }
  }

  function targetBitrate(height, fps) {
    const base = height >= 1080 ? 5000000 : height >= 720 ? 2500000 : 1200000;
    return Math.round(base * (fps > 30 ? 1.6 : 1));
  }

  async function applyConfig() {
    if (!state.stream) return;
    const track = state.stream.getVideoTracks()[0];
    const { height, fps } = state.cfg;
    try { await track.applyConstraints({ frameRate: { ideal: fps, max: fps } }); } catch (e) { /* best effort */ }
    if (!state.pc) return;
    const sender = state.pc.getSenders().find((s) => s.track && s.track.kind === 'video');
    if (!sender) return;
    try {
      const params = sender.getParameters();
      if (!params.encodings || !params.encodings.length) params.encodings = [{}];
      const actual = (track.getSettings && track.getSettings().height) || height;
      params.encodings[0].scaleResolutionDownBy = actual > height ? actual / height : 1;
      params.encodings[0].maxFramerate = fps;
      params.encodings[0].maxBitrate = targetBitrate(height, fps);   // WebRTC still adapts below this
      await sender.setParameters(params);
    } catch (e) { log('Could not apply quality settings: ' + e.message); }
  }

  function closePeer() {
    clearTimeout(state.discTimer);
    state.pending = [];
    if (state.pc) {
      const pc = state.pc;
      state.pc = null;
      pc.onicecandidate = pc.onconnectionstatechange = null;
      try { pc.close(); } catch (e) { /* ignore */ }
    }
  }

  function stop(reason, kind) {
    if (!state.casting && !state.stream) return;
    state.casting = false;
    clearTimeout(state.retryTimer);
    send({ type: 'bye' });
    if (state.ws) {
      const ws = state.ws;
      state.ws = null;
      try { ws.close(); } catch (e) { /* ignore */ }
    }
    closePeer();
    if (state.stream) { state.stream.getTracks().forEach((t) => t.stop()); state.stream = null; }
    $('preview').srcObject = null;
    updateButtons();
    setStatus(reason || 'Stopped', kind || 'idle');
  }

  window.addEventListener('beforeunload', () => { if (state.casting) stop('Closed', 'idle'); });
  $('startBtn').addEventListener('click', start);
  $('stopBtn').addEventListener('click', () => stop('Casting stopped', 'idle'));
  $('code').addEventListener('keydown', (e) => { if (e.key === 'Enter') start(); });
  $('code').addEventListener('input', (e) => { e.target.value = e.target.value.replace(/\D/g, '').slice(0, 6); });
  try { $('code').value = localStorage.getItem('wifitv.code') || ''; } catch (e) { /* ignore */ }
  fetch('/api/info').then((r) => r.json()).then((i) => { $('addr').textContent = i.ip + ':' + i.port; })
    .catch(() => { $('addr').textContent = '<this computer\'s IP>:' + location.port; });
  updateButtons();
})();
