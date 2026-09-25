/**
 * 播放狀態偵測橋接 —— 由 MainActivity 在每次 onPageFinished 注入。
 * 不用改 TPX1000 的 index.html，就能讓外殼知道現在有沒有在播音訊，
 * 進而決定要不要啟動前台服務（關螢幕繼續播）或關掉它（省電）。
 */
(function () {
  if (window.__tpxPlaybackBridge) return;
  window.__tpxPlaybackBridge = true;

  var timer = null;
  var lastState = null;

  function anyPlaying() {
    var media = document.querySelectorAll('audio, video');
    for (var i = 0; i < media.length; i++) {
      var m = media[i];
      if (!m.paused && !m.ended && m.readyState > 2) return true;
    }
    try {
      if (window.speechSynthesis && window.speechSynthesis.speaking) return true;
    } catch (e) {}
    return false;
  }

  function report() {
    if (timer) clearTimeout(timer);
    timer = setTimeout(function () {
      var playing = anyPlaying();
      if (playing === lastState) return;
      lastState = playing;
      try { TPXShell.onPlaybackState(playing); } catch (e) {}
    }, 300);
  }

  ['play', 'playing', 'pause', 'ended', 'emptied', 'waiting'].forEach(function (ev) {
    document.addEventListener(ev, report, true);
  });

  setInterval(function () {
    if (lastState || (window.speechSynthesis && window.speechSynthesis.speaking)) report();
  }, 1500);

  window.__tpxToggle = function () {
    var media = document.querySelectorAll('audio, video');
    var acted = false;
    for (var i = 0; i < media.length; i++) {
      var m = media[i];
      if (!m.paused && !m.ended) { m.pause(); acted = true; }
    }
    if (!acted) {
      for (var j = 0; j < media.length; j++) {
        if (media[j].currentTime > 0 && media[j].currentTime < media[j].duration) {
          media[j].play(); acted = true; break;
        }
      }
    }
    report();
    return acted;
  };

  report();
})();
