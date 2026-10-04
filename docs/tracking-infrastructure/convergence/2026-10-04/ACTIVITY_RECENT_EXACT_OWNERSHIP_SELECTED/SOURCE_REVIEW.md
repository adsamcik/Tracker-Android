# Independent source review — ordinary recent Activity ownership

Status: narrow static acceptance, IMPLEMENTED_UNVALIDATED. No compilation, tests, gates, Git mutation, providers or device work was executed.

Reviewed exact two-path selection following accepted N5 and the revised native ownership lookup:

- FILES.json: `f2d0a1342278d6d1d7705ba3fc19d3df72320633f2be1047763db07e2eb2e976`.
- SELECTION.patch: `0c17f97d888f1c80048fac1b5a33ef03869012d639b35c593e301d8dfdea4304`.
- SOURCE_BOUNDARY.md: `31806df104802cd34ca778d5d07f6633a55b6067e3df46260756cc132c33b5c5`.
- Selected repository: `809167b510dbb884ba31a67ed3d6f30bc690ebd76162735658d89e7fedeeaf12`.
- Selected test: `8d18064cf0ba8af2969417c690c57d5153a5f91afcf333cf75296d16ac0a3b56`.

Both selected snapshot hashes match the manifest. The previously open N6 source gap is repaired: ordinary recent imported comparison now resolves matching native owners from the actual imported entry/run/window/scope keys using the existing bounded lookup and exact source qualification. Off-screen ownership supplies only identity/protected-data comparison. Native presentation rows remain the original local page; their ordering/frontier and final cutoff are unchanged. Exact owner failures and budget limits retain existing typed outcomes. The N5 exact native selectors and value-free protected-window helper remain present.

The authored actual Room scenario places an original retained native window behind 101 newer factless owners, verifies it is outside the 100-row presentation frontier, then imports an independent future entry and a structurally valid foreign claim reusing only that old window. It checks the independent entry stays readable, ordinary history rejects the claim without values, and the shared bridge reports the corresponding integrity failure. Existing helper defaults remain unchanged. This scenario is unexecuted.

No additional blocker was found in this narrow companion. This receipt closes the N6 static review finding only; it does not declare Activity/History, all 25 full chains, validation or integration complete. Earlier N4/N5 rejection and acceptance receipts remain preserved.
