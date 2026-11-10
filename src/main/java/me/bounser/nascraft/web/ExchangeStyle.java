package me.bounser.nascraft.web;

/** The exchange stylesheet: a counting house. Walnut frame, green baize board, brass fittings, ledger-cream ink. */
final class ExchangeStyle {

    private ExchangeStyle() { }

    static final String CSS = """
            :root { --brass: #e0b552; --brass-d: #8a5a1a; --ink: #f2e6cc; --dim: #b8a882; --up: #7be07b; --down: #ff7a6a;
                    --felt: #16301f; --felt-d: #0e2015; }
            * { box-sizing: border-box; }
            html, body { margin: 0; height: 100%; }
            body { display: flex; align-items: center; justify-content: center; overflow: hidden; cursor: default;
                   font-family: Minecraft, monospace; font-size: 16px; color: var(--ink); background: transparent; }
            [data-action], [data-command], a[href] { cursor: pointer; }
            a { text-decoration: none; color: inherit; }
            p { margin: 0; }
            b { font-weight: normal; color: #fff; }
            small { font-size: 12px; color: var(--dim); }
            .spacer { flex: 1; }

            /* The window: walnut panelling with a brass rule, the board inside is baize. */
            .window { position: relative; width: 1120px; display: flex; flex-direction: column; gap: 8px; padding: 0 0 10px;
                      background: linear-gradient(180deg, rgba(0, 0, 0, 0.1) 0%, rgba(0, 0, 0, 0.45) 100%), url(nascraft-wood.svg);
                      background-size: auto, 96px 96px;
                      box-shadow: 0 0 0 2px #000, 0 0 0 5px #4a2e16, 0 0 0 6px var(--brass-d), 0 0 0 8px #000,
                                  0 20px 60px rgba(0, 0, 0, 0.7); }

            /* Header: a brass nameplate. */
            .head { display: flex; align-items: center; gap: 10px; height: 58px; padding: 0 12px;
                    background: linear-gradient(180deg, #3a2614 0%, #24170c 60%, #1a1009 100%);
                    box-shadow: inset 0 2px 0 rgba(255, 220, 150, 0.2), inset 0 -3px 0 #000, 0 3px 0 var(--brass-d), 0 5px 0 #000; }
            .crest { width: 44px; height: 44px; }
            .titles { display: flex; flex-direction: column; gap: 2px; }
            .title { font-size: 24px; color: var(--brass); letter-spacing: 1px; text-shadow: 2px 2px 0 #3a1e00, 0 0 10px rgba(224, 181, 82, 0.45); }
            .sub { font-size: 13px; color: var(--dim); }
            .chip { display: flex; align-items: center; gap: 6px; padding: 4px 10px; font-size: 15px; white-space: nowrap;
                    background: rgba(0, 0, 0, 0.55); box-shadow: 0 0 0 2px #000, inset 0 0 0 1px rgba(224, 181, 82, 0.35); }
            .chip img { width: 18px; height: 18px; }
            .chip em { font-style: normal; font-size: 13px; color: var(--dim); }
            .chip.up em { color: var(--up); }
            .chip.down em { color: var(--down); }
            .chip.link:hover { box-shadow: 0 0 0 2px #000, inset 0 0 0 1px var(--brass), 0 0 8px rgba(224, 181, 82, 0.5); }

            /* Buttons: brass-capped wood. */
            .btn { display: inline-flex; align-items: center; justify-content: center; gap: 6px; padding: 5px 14px; color: #fff; white-space: nowrap;
                   text-shadow: 2px 2px 0 #1a1008;
                   background: linear-gradient(180deg, #6a4a2c 0%, #4a321c 100%);
                   box-shadow: 0 0 0 2px #000, inset 2px 2px 0 #8c6a44, inset -2px -3px 0 #2a1a0c; }
            .btn:hover { background: linear-gradient(180deg, #e8c26a 0%, #a8742a 100%); color: #2a1600; text-shadow: none;
                         box-shadow: 0 0 0 2px #000, 0 0 10px rgba(224, 181, 82, 0.7), inset 2px 2px 0 #fff0c0, inset -2px -3px 0 #6a4210; }
            .btn em { font-style: normal; padding: 0 6px; background: rgba(0, 0, 0, 0.45); color: var(--brass); }
            .btn.small { padding: 2px 9px; font-size: 14px; }
            .btn.red { background: linear-gradient(180deg, #a83a32 0%, #642018 100%); }
            .btn.green { background: linear-gradient(180deg, #4f9a3f 0%, #2a6020 100%); }
            .btn.x:hover { background: linear-gradient(180deg, #c04848 0%, #802020 100%); color: #fff; }
            .btn.back { font-size: 18px; padding: 0 10px; }

            /* News tape: a strip of ticker paper. */
            .tape { display: flex; align-items: center; gap: 18px; height: 30px; margin: 0 12px; padding: 0 10px; overflow: hidden;
                    background: linear-gradient(180deg, #e9dcb8 0%, #d8c79c 100%); color: #3a2a14; font-size: 14px;
                    box-shadow: 0 0 0 2px #000, inset 0 -2px 0 rgba(0, 0, 0, 0.15); }
            .tape .label { padding: 2px 8px; background: #3a2a14; color: var(--brass); font-size: 13px; letter-spacing: 1px; }
            .tape .story { display: flex; align-items: center; gap: 6px; white-space: nowrap; }
            .tape .story small { color: #7a6a4a; }
            .tape .story i { width: 0; height: 0; border-left: 5px solid transparent; border-right: 5px solid transparent; }
            .tape .story.up i { border-bottom: 8px solid #2a8a2a; }
            .tape .story.down i { border-top: 8px solid #b03020; }
            .tape .story.empty { display: none; }
            .tape .quiet { display: none; color: #7a6a4a; }
            .tape .story.empty + .story.empty + .story.empty + .quiet { display: inline; }

            /* Category tabs. */
            .tabs { display: flex; gap: 6px; padding: 0 12px; }
            .tab { padding: 4px 14px; font-size: 15px; color: var(--dim);
                   background: rgba(0, 0, 0, 0.45); box-shadow: 0 0 0 2px #000, inset 0 0 0 1px rgba(224, 181, 82, 0.2); }
            .tab:hover { color: #fff; box-shadow: 0 0 0 2px #000, inset 0 0 0 1px var(--brass); }
            .tab.on { color: #2a1600; background: linear-gradient(180deg, #fff0b0 0%, #e0b552 55%, #9a6a14 100%);
                      box-shadow: 0 0 0 2px #000, inset 2px 2px 0 #fff8d0, inset -2px -2px 0 #6a4210; }

            /* The board: baize with goods pinned on it. */
            .board { display: flex; flex-wrap: wrap; align-content: flex-start; gap: 10px; margin: 0 12px; padding: 10px; height: 418px;
                     background: url(nascraft-baize.svg); background-size: 32px 32px;
                     box-shadow: 0 0 0 2px #000, inset 0 0 0 2px #2a4a32, inset 0 6px 16px rgba(0, 0, 0, 0.55); }
            .card { position: relative; width: 258px; height: 124px; padding: 8px 10px; display: flex; flex-direction: column; gap: 3px;
                    background: linear-gradient(180deg, rgba(14, 30, 20, 0.95) 0%, rgba(8, 18, 12, 0.95) 100%);
                    box-shadow: 0 0 0 2px #000, inset 0 0 0 1px rgba(224, 181, 82, 0.28), 0 4px 8px rgba(0, 0, 0, 0.45); }
            .card:hover { box-shadow: 0 0 0 2px #000, inset 0 0 0 1px var(--brass), 0 0 12px rgba(224, 181, 82, 0.45); }
            .card .top { display: flex; align-items: center; gap: 8px; }
            .icon { width: 32px; height: 32px; }
            .icon.big { width: 48px; height: 48px; }
            .card .nm { display: flex; flex-direction: column; min-width: 0; }
            .card .nm span { font-size: 16px; white-space: nowrap; overflow: hidden; }
            .card .mid { display: flex; align-items: baseline; gap: 8px; }
            .px { display: flex; align-items: center; gap: 5px; font-size: 20px; color: #fff; }
            .px img { width: 16px; height: 16px; }
            .chg { font-size: 14px; color: var(--dim); }
            .up .chg, .chg.up { color: var(--up); }
            .down .chg, .chg.down { color: var(--down); }
            .card .spark { position: absolute; left: 19px; bottom: 6px; width: 220px; height: 40px; opacity: 0.95; }
            .reg { color: var(--dim); }
            .bull-run .reg, .shortage .reg { color: var(--up); }
            .slump .reg, .glut .reg { color: var(--down); }
            .book .reg { color: #9ec8ff; }

            .foot { display: flex; align-items: center; gap: 10px; padding: 0 12px; }
            .hint { font-size: 12px; color: var(--dim); }

            /* Good page: chart on the left, trading desk on the right. */
            .split { display: flex; gap: 12px; padding: 0 12px; height: 500px; }
            .left { width: 704px; display: flex; flex-direction: column; gap: 8px; }
            .right { flex: 1; display: flex; flex-direction: column; gap: 8px; min-width: 0; }
            .quote { display: flex; align-items: center; gap: 12px; padding: 6px 10px;
                     background: rgba(0, 0, 0, 0.5); box-shadow: 0 0 0 2px #000, inset 0 0 0 1px rgba(224, 181, 82, 0.3); }
            .quote .qv { display: flex; align-items: baseline; gap: 12px; }
            .quote .px { font-size: 28px; }
            .quote .px img { width: 22px; height: 22px; }
            .pill { padding: 3px 10px; font-size: 14px; color: var(--ink); background: rgba(224, 181, 82, 0.15);
                    box-shadow: 0 0 0 2px #000, inset 0 0 0 1px rgba(224, 181, 82, 0.5); }
            .bull-run .pill, .shortage .pill { color: var(--up); box-shadow: 0 0 0 2px #000, inset 0 0 0 1px var(--up); }
            .slump .pill, .glut .pill { color: var(--down); box-shadow: 0 0 0 2px #000, inset 0 0 0 1px var(--down); }
            .chart { position: relative; height: 300px; padding: 22px 12px 8px;
                     background: url(nascraft-baize.svg); background-size: 32px 32px;
                     box-shadow: 0 0 0 2px #000, inset 0 0 0 2px #2a4a32, inset 0 6px 16px rgba(0, 0, 0, 0.55); }
            .chart .plot { width: 680px; height: 270px; }
            .chart .ranges { position: absolute; right: 8px; top: 6px; display: flex; gap: 4px; }
            .chart .ranges .tab { padding: 1px 8px; font-size: 13px; }
            .stats { display: flex; gap: 8px; }
            .stats > div { flex: 1; display: flex; flex-direction: column; gap: 2px; padding: 5px 8px; font-size: 15px;
                           background: rgba(0, 0, 0, 0.5); box-shadow: 0 0 0 2px #000, inset 0 0 0 1px rgba(224, 181, 82, 0.2); }
            .panel { display: flex; flex-direction: column; gap: 6px; padding: 8px 10px;
                     background: linear-gradient(180deg, rgba(14, 30, 20, 0.95) 0%, rgba(8, 18, 12, 0.95) 100%);
                     box-shadow: 0 0 0 2px #000, inset 0 0 0 1px rgba(224, 181, 82, 0.3); }
            .ph { font-size: 15px; color: var(--brass); letter-spacing: 1px; padding-bottom: 4px;
                  box-shadow: inset 0 -1px 0 rgba(224, 181, 82, 0.3); }
            .row { display: flex; align-items: center; gap: 6px; }
            .lbl { width: 52px; font-size: 13px; color: var(--dim); }
            .btn.trade { flex: 1; flex-direction: column; gap: 0; padding: 3px 4px; min-width: 0; }
            .btn.trade b { font-size: 14px; color: inherit; }
            .btn.trade span { font-size: 12px; color: #e8dcc0; }
            .btn.trade:hover span { color: #3a2000; }
            .btn.trade.buy { background: linear-gradient(180deg, #3f7a34 0%, #24501c 100%); box-shadow: 0 0 0 2px #000, inset 2px 2px 0 #6aa25a, inset -2px -3px 0 #183812; }
            .btn.trade.sell { background: linear-gradient(180deg, #8a3a2a 0%, #5a1e14 100%); box-shadow: 0 0 0 2px #000, inset 2px 2px 0 #b06a50, inset -2px -3px 0 #3a120a; }
            .btn.trade:hover { background: linear-gradient(180deg, #e8c26a 0%, #a8742a 100%); }
            .btn.sel { min-width: 90px; }
            .position { display: flex; gap: 6px; margin-top: 2px; }
            .position > div { flex: 1; display: flex; flex-direction: column; gap: 1px; padding: 4px 6px; font-size: 14px; background: rgba(0, 0, 0, 0.4); }
            .position.none { opacity: 0.55; }
            .position.up .pl { color: var(--up); }
            .position.down .pl { color: var(--down); }
            .bookbox { display: flex; gap: 10px; }
            .bookbox .side { flex: 1; display: flex; flex-direction: column; align-items: center; gap: 4px; padding: 18px 10px;
                             background: rgba(0, 0, 0, 0.5); box-shadow: 0 0 0 2px #000, inset 0 0 0 1px rgba(224, 181, 82, 0.25); }
            .bookbox b { font-size: 30px; }
            .bookbox .ask b { color: var(--down); }
            .bookbox .bid b { color: var(--up); }
            .bookbox span { font-size: 13px; color: var(--dim); }
            .note { font-size: 13px; line-height: 18px; color: var(--dim); padding: 4px 2px; }
            .note.small { font-size: 12px; }

            /* Ledger pages: portfolio and orders. */
            .summary { display: flex; align-items: center; gap: 10px; margin: 0 12px; }
            .summary > div { display: flex; flex-direction: column; gap: 2px; padding: 6px 14px; font-size: 20px; min-width: 170px;
                             background: rgba(0, 0, 0, 0.5); box-shadow: 0 0 0 2px #000, inset 0 0 0 1px rgba(224, 181, 82, 0.3); }
            .summary.up .pl { color: var(--up); }
            .summary.down .pl { color: var(--down); }
            .table { display: flex; flex-direction: column; gap: 3px; margin: 0 12px; padding: 8px; height: 420px;
                     background: linear-gradient(180deg, #e9dcb8 0%, #ddcca2 100%); color: #3a2a14;
                     box-shadow: 0 0 0 2px #000, inset 0 0 0 3px #c8b480; }
            .tr { display: flex; align-items: center; gap: 8px; height: 34px; padding: 0 8px; font-size: 15px;
                  box-shadow: inset 0 -1px 0 rgba(90, 60, 20, 0.25); }
            .tr > span { width: 104px; text-align: right; white-space: nowrap; }
            .tr > span.g { flex: 1; text-align: left; }
            .tr > span.act { width: 150px; display: flex; justify-content: flex-end; gap: 6px; }
            .tr.th { height: 26px; font-size: 13px; color: #7a6a4a; box-shadow: inset 0 -2px 0 rgba(90, 60, 20, 0.45); }
            .tr.empty { display: none; }
            .tr.up .pl { color: #1e7a1e; }
            .tr.down .pl { color: #a82818; }
            .tr.buy .side { color: #1e7a1e; }
            .tr.sell .side { color: #a82818; }
            .empty-note { display: none; padding: 30px; text-align: center; color: #7a6a4a; }
            .th + .tr.empty + .tr.empty + .tr.empty + .tr.empty + .tr.empty + .tr.empty + .tr.empty + .tr.empty + .tr.empty + .tr.empty + .empty-note { display: block; }
            """;
}
