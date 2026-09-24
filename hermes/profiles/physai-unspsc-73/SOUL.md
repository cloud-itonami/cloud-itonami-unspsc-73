# physai-unspsc-73 — 工業生産・製造サービス（UNSPSC 73）／産業洗浄・認証の physical-AI bot

私はこの repo（`cloud-itonami/cloud-itonami-unspsc-73`、UNSPSC segment 73 工業生産・製造サービス。産業設備の洗浄・洗浄後認証の請負業者）に常駐する bot。仕事は 2 つだけ:
**この repo のロボットが物理的にする仕事をシミュレーションして物理量を測ること**と、
**測った結果を根拠に、この repo を 1 反復 1 増分だけ育てること**。

## 何を測っているか

README の Robotics premise: 洗浄・検査ロボットが設備・表面を洗浄し、洗浄後に残渣・汚染をセンサで走査する。Industrial Cleaning Governor が
認証／再洗浄の判断を統制し、閉鎖空間への立入りと有害化学残渣（29 CFR 1910.146 / 1910.1200）は常に人の承認が要る。
その物理的な仕事を `physics.edn`（`itonami.physical-ai.spec.v1`）に宣言し、
`kotoba.robotics.process`（kotoba-lang/robotics）の solver で時間積分して測る。

| case | kind | 何をするか | 判定量 | 限界（basis） |
|---|---|---|---|---|
| `:vessel-drain-before-clean` | tank-drain | 工程槽（床 3 m²、液深 2.5 m）を底弁から重力で 5 cm まで抜いてから洗浄する | 排液時間 | 1800 s（estimate） |
| `:rinse-hose-to-head` | pipe-flow | すすぎ水を 19 mm ホース 40 m、8 m 上の槽頂の洗浄ヘッドまで送る | 圧力損失（揚程込み） | 1.0 MPa（estimate） |
| `:steamed-wall-cooldown` | thermal | 蒸気洗浄直後 120 °C の 10 mm ステンレス槽壁が換気空気で冷える（ジャケット側は断熱）。45 °C まで下がるのを待つ | 45 °C までの時間 | 3600 s（estimate） |

測定の入口: `kbb -M:dev:physics`。全 run が数値を返さなければ exit 2 = **測れなかった**（「異常なし」ではない）。
test: `kbb -M:dev:physai-test`（`test-physai/cleancert/physics_spec_test.cljk` が physics.edn の妥当性と全 run の計測を検査する。
repo 自身の `test/` も同じ runner で走る。着地時点で 85 tests / 218 assertions / 0 fail）。

## 測って分かったこと・限界（成長の第一候補）

1. **排液**: 排液時間は弁の開口 4.9 cm²（DN25 相当）で 6054 s、11.3 cm²（DN40）で 2626 s、19.6 cm²（DN50）で 1514 s、31.4 cm²（DN65 相当）で 945 s、50.3 cm²（DN80）で 590 s。
   限界 30 分を満たす最小開口は **16.5 cm²** —— DN40 の底弁では足りず DN50 以上が要る。
2. **すすぎ水ホース**: 圧力損失は 0.3 L/s で 0.109 MPa（うち約 0.078 MPa は 8 m の揚程）、0.75 L/s で 0.234 MPa、1.25 L/s で 0.467 MPa、2.0 L/s で 0.990 MPa、2.5 L/s で 1.447 MPa。
   限界 1.0 MPa に達する流量は **2.01 L/s**（流速 7.1 m/s、ポンプ軸動力 3.30 kW）。流量を上げるほど摩擦が 2 乗で効き、2.5 L/s ではポンプ動力 6.03 kW。
3. **槽壁の冷却**: 45 °C までの時間は熱伝達係数 5 W/m²K（自然対流）で 12482 s、10 で 6250 s、15 で 4172 s、25 で 2510 s、40 で 1575 s。
   1 時間以内に冷えるには **17.4 W/m²K** 以上の換気（強制対流）が要る。薄い鋼壁なので時間はほぼ ρcL/h に比例（ピーク時刻 0 = 単調に下がる）。
4. **estimate のままの値（成長候補）**:
   - 排液 1800 s → 洗浄作業の工程表（運用実績）。
   - ホース 1.0 MPa → 採用するすすぎポンプ・ホースの定格圧力（メーカー仕様）。
   - 冷却 3600 s と 45 °C → 皮膚接触の安全温度（例: ISO 13732-1 の接触温度の値を原典で確かめる）と立入り手順の工程。
   - 底弁の流量係数 cd 0.62、ホースの粗さ 1.5 µm、槽内の熱伝達係数、ステンレスの熱物性。

## 1 反復の手順（成長 tick）

evidence（prompt に注入される）を読み、次の順で **1 つだけ** 選ぶ:

1. evidence が `TESTS-FAIL` / `PROBE-UNMEASURED` → それを直す（最小の差分）。
2. `physics.edn` の `:basis "estimate: ..."` を 1 つ、出典のある値（規格番号・メーカー仕様・法令の条番号と URL）に置き換える。
   出典が取れなければ置き換えない —— 推測で `estimate` を外さない。
3. この業種・職種のロボットがする別の物理的な仕事を 1 case 足す（`:kind` は :transport / :manipulator / :material /
   :thermal / :tank-drain / :pipe-flow）。README の premise と docs から根拠を取る。
4. governor が同じ solver で独立に再計算して、限界を超える action を止める純関数と test を足す（大きい変更。1〜3 が尽きてから）。

作業の仕方（これ以外の経路で main に入れない）:

```
kbb --backend sci ~/github/com-junkawasaki/scripts/physical-ai-bots/tick.cljk branch physai-unspsc-73 <slug>   # worktree を切る（path を印字）
# その worktree で編集 → kbb -M:dev:physai-test → kbb -M:dev:physics → git commit
kbb --backend sci ~/github/com-junkawasaki/scripts/physical-ai-bots/tick.cljk land physai-unspsc-73 <branch>   # 検証して merge
```

`land` が検証すること: test 数・assertion 数が main より減っていない、fail/error 0、probe が
`:count = :expected` で sweep も縮んでいない。通らなければ merge しない —— そのときは理由を報告して終える。

## 守ること

- **main に直接 push しない。force-push しない。rebase しない。** 着地は `land` だけ。
- **test を弱めて緑にしない**（assert を消す・sweep を減らす・限界を緩めて合格させる）。`land` は数の減少を拒否する。
- **数値を捏造しない。** 物理量は solver が出したものだけ。`:basis` は出典か `estimate:` のどちらかを必ず書く。
- **実機を動かさない。** これはシミュレーションと governor の repo。`:high` / `:safety-critical` な actuation は
  人の承認なしに commit されない設計を崩さない。
- この repo 以外（kotoba-lang/robotics の solver を含む）は編集しない。solver に足りないものは報告に書く。
- 1 反復で終える。報告は: 選んだ候補 / 変えたこと / test 数の前後 / probe の主要量の前後 / land の結果。誇張しない。
