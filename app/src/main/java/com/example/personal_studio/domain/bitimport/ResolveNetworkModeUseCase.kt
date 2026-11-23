package com.example.personal_studio.domain.bitimport

import com.example.personal_studio.data.network.bit.CampusReachabilityProbe
import com.example.personal_studio.data.network.bit.NetworkMode
import javax.inject.Inject

/** Which school systems a task talks to, for choosing its first route. */
enum class SchoolRoute {
    /**
     * 教学中心 (课表、考试、空教室) and the sign-in that checks a password through it. Its hosts
     * are on the public internet, so going direct works anywhere and skips WebVPN, which is slower
     * and has its own sign-in. A connection failure still falls back to WebVPN.
     */
    PUBLIC,

    /** 成绩 (jwms) and 乐学: their names resolve only on campus DNS, so off campus they need WebVPN. */
    CAMPUS,
}

/**
 * 决定本次同步的**首选**网络模式(全自动,校内优先;已无手动选择)。
 *
 * [SchoolRoute.CAMPUS] 每次先短时探测 jwms 是否可直连；历史模式不能代表当前网络。
 * 这样从校园 Wi-Fi 切到移动数据后，在登录之前就能选择 WebVPN。
 * [SchoolRoute.PUBLIC] 不探测，直接直连（教学中心在公网可达）。
 */
class ResolveNetworkModeUseCase @Inject constructor(
    private val probe: CampusReachabilityProbe,
) {
    suspend operator fun invoke(
        @Suppress("UNUSED_PARAMETER") lastMode: NetworkMode?,
        route: SchoolRoute = SchoolRoute.CAMPUS,
    ): NetworkMode {
        if (route == SchoolRoute.PUBLIC) return NetworkMode.LOCAL
        // Keep the caller API stable; the previous success is only persisted as history.
        return if (probe.localReachable()) NetworkMode.LOCAL else NetworkMode.WEBVPN
    }
}
