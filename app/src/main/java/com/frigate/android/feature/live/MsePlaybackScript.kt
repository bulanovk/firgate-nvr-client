package com.frigate.android.feature.live

internal fun mseSupportedCodecsScript(): String = """
    var codecs = [
      'avc1.640029',
      'avc1.64002A',
      'avc1.640033',
      'hvc1.1.6.L153.B0',
      'hev1.1.6.L153.B0',
      'mp4a.40.2',
      'mp4a.40.5',
      'flac',
      'opus'
    ];
""".trimIndent()

internal fun mseBufferMaintenanceScript(): String = """
    if (sourceBuffer.mode) sourceBuffer.mode = 'segments';
    sourceBuffer.addEventListener('updateend', function() {
      pump();
      if (sourceBuffer.updating || !sourceBuffer.buffered || sourceBuffer.buffered.length === 0) {
        return;
      }
      var end = sourceBuffer.buffered.end(sourceBuffer.buffered.length - 1) - 15;
      var start = sourceBuffer.buffered.start(0);
      if (end - start > 1) {
        try {
          sourceBuffer.remove(start, end);
        } catch (error) {
          status('MSE buffer cleanup error');
        }
      }
      revealVideoAfterStableBuffer();
    });
""".trimIndent()

internal fun mseFirstFrameRevealScript(): String = """
    function revealVideoAfterStableBuffer() {
      var reveal = function() {
        if (stopped) {
          startupRevealTimer = null;
          return;
        }
        if (video.readyState >= 2 && video.buffered.length > 0) {
          var end = video.buffered.end(video.buffered.length - 1);
          var bufferedSeconds = end - video.currentTime;
          if (bufferedSeconds >= 1) {
            snapshotVisible = false;
            still.style.display = 'none';
            video.style.visibility = 'visible';
            startupRevealTimer = null;
            status('Playing');
            return;
          }
        }
        startupRevealTimer = setTimeout(reveal, 100);
      };
      if (startupRevealTimer) clearTimeout(startupRevealTimer);
      reveal();
    }
    video.onplaying = revealVideoAfterStableBuffer;
    video.onloadeddata = revealVideoAfterStableBuffer;
    video.onwaiting = function() {
      status('Buffering');
    };
    video.onerror = function() {
      if (startupRevealTimer) clearTimeout(startupRevealTimer);
      startupRevealTimer = null;
      keepSnapshotVisible(true);
      status('MSE playback error');
    };
""".trimIndent()
