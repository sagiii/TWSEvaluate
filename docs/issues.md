# 1: 録音再生にシークが欲しい

音量レベルがシークにオーバーレイされているようなよくあるUI希望

- [x] 対応済み (2026-09-29): セッション詳細画面の会議録音再生に、波形オーバーレイ付きのシークバー(タップ/ドラッグでシーク可)を追加。`WaveformExtractor` / `WaveformSeekBar` / `SessionDetailScreen`。実機で再生位置追従を確認済み。

# 2: READMEを作成してほしい

プロジェクトのREADMEがまだ無い。諸々の機能が落ち着いてからでOK。

# 3: 音楽再生 音量グラフ取得の高速化希望

いまだと数分のトラックのグラフ出現まで数秒ラグあり

- [x] 対応済み (2026-09-29): `AudioWaveformDecoder`を曲全体フルデコードから、区間ごとに
  `seekTo`+`flush`で短いウィンドウだけデコードするサンプリング方式に変更。曲の長さに
  依存せずほぼ一定時間で波形が出るようになった。

# 4: 音楽再生のシークがずれる(未解決)

**症状**: 音楽モードで曲の波形シークバーをタップ/ドラッグしても、意図した位置に
再生位置が飛ばない。ユーザーの見立て:「1曲目をロード後、2曲目再生時に横軸の縮尺が
直っていないのでは」。

**試したが解決しなかった対応 (2026-09-29)**:
- `MediaPlayer.seekTo(int)`(直前の同期点にスナップする粗いシーク)を
  `seekTo(long, SEEK_CLOSEST)`(フレーム精度)に変更。`MusicPlayer.kt` /
  `SessionDetailScreen.kt`の両方。→ 改善せず。
- 曲切り替え直後、`musicPositionMs`が前の曲の値のまま次のポーリング(最大150ms後)まで
  残ってしまう窓を見つけ、切り替え時に即座に0リセットするよう修正
  (`EvaluationSessionScreen.kt`の`onPlayBundledTrack`/`audioPicker`コールバック)。
  → これは一瞬のズレしか説明できず、症状は変わらず。

**未確認・次に調べるべきこと**:
- `durationMs`(`EvaluationSessionService.musicDurationMs`、`MediaPlayer.duration`由来)が
  曲によって本当に正しく更新されているか(StateFlowの値が実際にどう遷移するかログで確認)
- タップ位置と実際のジャンプ先の関係が「比例的にズレる(スケール自体が違う)」のか
  「一定時間分だけズレる(オフセットの問題)」のか切り分けが必要
  (例: 中央タップで25%や75%に飛ぶなら倍率のバグ、常に数秒〜十数秒だけずれるなら
  オフセット系のバグ=AACのエンコーダディレイ/パディングをMediaPlayerが考慮していない
  可能性などを疑う)
- 同梱曲(AAC, ffmpeg由来でiTunSMPB等のgapless情報なし)特有の問題か、端末内の任意ファイル
  でも同様に起きるか
- `WaveformSeekBar`のCanvas座標(`pointerInput`のsizeとDrawScopeのsizeの対応)自体は
  会議録音側(`SessionDetailScreen`)では問題ないとされているため、音楽再生固有の経路
  (`EvaluationSessionScreen`側の`durationMs`/`positionMs`の受け渡し)を優先して疑うべき

