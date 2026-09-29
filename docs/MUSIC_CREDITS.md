# 同梱BGMの出典 (2026-09-29 差し替え)

手続き生成した仮音源(`tools/generate_bundled_music.py`)から、[Pixabay](https://pixabay.com/music/) の
実在の高品質音源に差し替えた。すべて **Pixabay Content License** の下で提供されている素材で、
無料・クレジット表記不要・改変可(単体での再配布のみ禁止、本アプリのようにアプリへ組み込む用途は問題ない)。
ライセンス全文: https://pixabay.com/service/license-summary/

生成音源に戻したい場合は`tools/generate_bundled_music.py`を参照。ダウンロード手順は本ファイル末尾に記載。

## 各曲の出典ページ

| ファイル | 出典ページ |
|---|---|
| music_pop_1.m4a | https://pixabay.com/music/pop-pop-pop-music-576583/ |
| music_pop_2.m4a | https://pixabay.com/music/synth-pop-synth-pop-593073/ |
| music_pop_3.m4a | https://pixabay.com/music/dance-pop-pop-music-503314/ |
| music_rock_1.m4a | https://pixabay.com/music/rock-rock-rock-music-576586/ |
| music_rock_2.m4a | https://pixabay.com/music/rock-energetic-rock-593043/ |
| music_rock_3.m4a | https://pixabay.com/music/rock-indie-rock-606263/ |
| music_jazz_1.m4a | https://pixabay.com/music/modern-jazz-jazz-song-sunny-cafe-nu-jazz-587413/ |
| music_jazz_2.m4a | https://pixabay.com/music/modern-jazz-jazz-piano-578722/ |
| music_jazz_3.m4a | https://pixabay.com/music/modern-jazz-cool-jazz-598432/ |
| music_classical_1.m4a (純粋ピアノ) | https://pixabay.com/music/classical-piano-beethoven-moonlight-sonata-1-movement-op-27-nr-2-180627/ (Beethoven - Moonlight Sonata, 1st mvt., real classical recording by GregorQuendel) |
| music_classical_2.m4a (純粋弦楽) | https://pixabay.com/music/chamber-music-string-quartet-elegance-537464/ |
| music_classical_3.m4a (フルオーケストラ) | https://pixabay.com/music/classical-string-quartet-bach-violin-concerto-in-a-minor-1-movement-bwv-1041-allegro-190655/ (Bach - Violin Concerto in A minor, 1st mvt., real classical recording by GregorQuendel) |
| music_edm_1.m4a | https://pixabay.com/music/future-bass-energy-edm-155588/ |
| music_edm_2.m4a | https://pixabay.com/music/beats-edm-fun-153644/ |
| music_edm_3.m4a | https://pixabay.com/music/electronic-edm-house-510192/ |
| music_lofi_1.m4a | https://pixabay.com/music/lofi-abstract-lofi-602638/ |
| music_lofi_2.m4a | https://pixabay.com/music/lofi-tokyo-lofi-604786/ |
| music_lofi_3.m4a | https://pixabay.com/music/lofi-lofi-relaxing-598427/ |
| music_ambient_1.m4a | https://pixabay.com/music/ambient-calm-ambient-dreamscape-529861/ |
| music_ambient_2.m4a | https://pixabay.com/music/ambient-ambient-piano-595681/ |
| music_ambient_3.m4a | https://pixabay.com/music/ambient-ambient-607644/ |
| music_hiphop_1.m4a | https://pixabay.com/music/old-school-hip-hop-hip-hop-groove-526148/ |
| music_hiphop_2.m4a | https://pixabay.com/music/beats-hip-hop-hip-hop-beat-547254/ |
| music_hiphop_3.m4a | https://pixabay.com/music/alternative-hip-hop-hip-hop-journey-567424/ |
| music_folk_1.m4a (acoustic) | https://pixabay.com/music/folk-acoustic-guitar-sunrise-travel-573651/ |
| music_folk_2.m4a (acoustic) | https://pixabay.com/music/folk-acoustic-folk-593025/ |
| music_folk_3.m4a (acoustic) | https://pixabay.com/music/instrumental-chill-acoustic-589690/ |
| music_bossa_1.m4a | https://pixabay.com/music/bossa-nova-bossa-nova-cafe-morning-breeze-573876/ |
| music_bossa_2.m4a | https://pixabay.com/music/bossa-nova-bossa-nova-587558/ |
| music_bossa_3.m4a | https://pixabay.com/music/bossa-nova-bossa-nova-sunny-cafe-552766/ |

## 加工内容
- ラウドネス正規化(`loudnorm=I=-14:TP=-1.5:LRA=11`)を全曲にかけ、曲ごとの音量差を軽減
- AAC 192kbps / 44.1kHz / ステレオに変換(元はMP3 256kbps程度)
- 尺・曲調は未加工(フル尺のまま同梱。ユーザー希望によりトリミングなし)

## 再ダウンロード手順(参考)
Pixabayはダウンロードにログイン不要だが、CDN上の実ファイルURLはブラウザセッションに
紐づく署名付きURLのため、`curl`などブラウザ外から直接叩くと403になる。差し替える場合は
実際にブラウザで該当ページを開き、ページのHTML内に埋め込まれた
`https://cdn.pixabay.com/download/audio/.../audio_*.mp3?filename=...` を取得してダウンロードする。
