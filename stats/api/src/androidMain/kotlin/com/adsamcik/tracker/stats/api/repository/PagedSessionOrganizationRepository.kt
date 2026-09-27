package com.adsamcik.tracker.stats.api.repository

import androidx.paging.PagingSource

interface PagedSessionOrganizationRepository : SessionOrganizationRepository {
	fun pagingSource(fromMs: Long?, toMs: Long?): PagingSource<Int, OrganizedSession>
}
