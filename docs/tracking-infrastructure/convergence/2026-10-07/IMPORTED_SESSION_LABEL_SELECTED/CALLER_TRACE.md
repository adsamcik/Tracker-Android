# Existing label edit call chain

Static trace, NOT_RUN.

- HistoryRoute:158 sends a selected OrganizedSession to SessionOrganizationViewModel.edit; editor setup keeps its saved metadata and epoch.
- HistoryRoute:177-179 already connects editor onSave directly to organization::save. Its function-reference arity matches the amended five-value editor callback and actual save method; no route redesign is selected.
- SessionOrganizationEditor initializes the selected local ID plus a transient clearImportedActivityLabel=false. Its existing displayed-label fallback now also works when an imported descriptive name has no local ID. No label sets null/true; a local choice sets its ID/false; existing Cancel and failed-save behavior retain their roles.
- SessionOrganizationViewModel.save forwards the explicit request only for editing an existing session. Creation still uses the unchanged create contract and stores no imported label. Its default is false, so existing direct saves preserve imported text.
- SessionOrganizationRepository.edit gains clearImportedActivityLabel=false. PagedSessionOrganizationRepository inherits that contract; there is one production implementation, RoomSessionOrganizationRepository. No other production edit caller or handwritten fake implementation exists in committed source.
- RoomSessionOrganizationRepository checks epoch, identity namespace, revision, visibility, active-recording restrictions and all existing input validation before building the updated annotation. Its existing importedActivityLabel is retained only when the clear request is false and the local activity ID is unchanged. No schema or authority mutation is added.
- The only affected existing mock edit declaration is SessionOrganizationViewModelTest's stale-save case, updated to six matchers. Existing Room edit calls inherit the default false. Four original Compose callbacks gain one ignored argument.
- StatsDataModule:82-90 binds both organization interfaces to the existing singleton repository. Existing module edges, Hilt, Paging, lifecycle, Room and resources support the repair without dependencies. Existing label/No label/error strings and layout are reused; no new visible copy or accessibility option is introduced.

Seven new methods: three editor cases (show/preserve, explicit clear through save failure, clear then local replacement), two ViewModel cases (default preserve, clear/retry forwarding), two Room cases (clear only metadata, stale clear refusal). Original name-only imported-label and local-label replacement Room assertions remain.
