# Independent background radio cleanup review

Reviewer: /root/daily_steps_display_fresh_review_1007. Original user messages,
approved data/erase/retention decisions, exact production/test selections and
committed dependencies were supplied. Combined V2 selection accepted.

Production FILES SHA-256:
717e6786ac690caaf437dc5e25093dce63f93e1957f28e0207a4670f8346b10c.
Production patch SHA-256:
e48be9408efa134e94a78bf24427223b1ff92835b540bbe667919fdffbe4f3d4.
V2 test source SHA-256:
7c95095be60bb7ba211673f9f314b8791e227736eae1b3ad14150a3c8f14a64b.
Test FILES SHA-256:
789c789d3bf68190f7b16ed2ed0d8aa93244f32271012179c9b2d5e1a74fbd30.
Test patch SHA-256:
e964ebe1ee3a35997720245d721aae86c5086264b98465d8546279e5bbdbc01a.

V1 review found a fixture-boundary error: +600 ms with 1 ms uncertainty put the
survivor's measured start below the retention floor. V2 consistently shifts the
fixture parent/receive/coverage/child clocks by +700 ms, retaining correct checksums
and the original provider retirement bound. Production qualification is unchanged.
Reviewed V1 remains preserved. All 38 source pins and both maintenance overlays
match; the superseded planning-only pin has an explicit historical disposition.
Root refreshed the production selection's nine pins at selection HEAD bf52dc1145.

Added child deletes use existing source/fact-specific DAO methods before parent
deletes inside existing Room transactions, following the same approved scope.
Epoch/authority refusals cannot reach the new deletes. Imported CASCADE and the
actual outer full-clear wrapper remain unchanged. Eight authored Room methods
cover purpose fencing/consent helper erase, preserving-product clear, exact prune
with newer same-source/other-source survivors, wrong-epoch refusal and outer clear.

PeriodicAmbientRetentionMaintenance reaches prune in production. Ordinary consent
reset and preserving-product clear helper calls currently have no production
callers in committed app/data/tracker/stats/core source; their test coverage does
not close consent-reset UX or collection lifecycle wiring. Captured sibling facts,
imported children, complete ingress-to-reader composition, public End/provider
retirement and Android behavior remain separate obligations. Full T11 stays open.
IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED. Every test NOT_RUN; no gates run.
