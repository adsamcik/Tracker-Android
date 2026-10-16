package com.adsamcik.tracker.stats.api.repository

import androidx.paging.PagingSource

/**
 * Pages visible session annotations and recording groups over an optional UTC millisecond range.
 */
interface PagedSessionOrganizationRepository : SessionOrganizationRepository {
	/**
	 * Creates a new offset-keyed session page source, with both UTC millisecond range bounds supplied
	 * or both null for all visible sessions.
	 */
	fun pagingSource(fromMs: Long?, toMs: Long?): PagingSource<Int, OrganizedSession>
}
