package local.voxfix.debug

import org.junit.Assert.*
import org.junit.Test

class HostSelectionRequestTest {
    @Test fun acceptsSortedReadbackOnlyOnce() {
        val request = HostSelectionRequest()
        request.request("notes", 24, 12, 0)
        assertEquals(24 to 12, request.take("notes", 12, 24, 10))
        assertNull(request.take("notes", 12, 24, 20))
    }

    // 换输入框（宿主不同）或请求过期，都不能把旧编辑框重新弹出来。
    @Test fun anotherFieldOrAnExpiredRequestCannotReopenTheEditor() {
        val request = HostSelectionRequest(3000)
        request.request("notes", 12, 24, 0)
        assertNull(request.take("chat", 12, 24, 10))
        assertTrue("另一个输入框不能核销掉这次请求", request.pending("notes", 10))
        assertNull(request.take("notes", 12, 24, 3001))
        request.request("notes", 12, 24, 5000); request.cancel(); assertNull(request.take("notes", 12, 24, 5010))
    }

    // 宿主在 show 期间把选区收成光标、或报不出端点（-1）：仍按请求里那份选区打开编辑框。
    // 旧的"端点必须完全相等"判定就是这么漏掉"长按选中多字却打不开编辑框"的。
    @Test fun aCollapsedOrMissingReadbackStillOpensTheRequestedWord() {
        val request = HostSelectionRequest()
        request.request("notes", 95, 103, 0)
        assertEquals(95 to 103, request.take("notes", 95, 95, 10))
        request.request("notes", 95, 103, 20)
        assertEquals(95 to 103, request.take("notes", -1, -1, 30))
        request.request("notes", 103, 95, 40)
        assertEquals(103 to 95, request.take("notes", 95, 103, 50))
    }

    // 宿主重新算过词边界、报出另一个有效非空选区：按宿主现状开，不能因为"不相等"整条丢掉。
    @Test fun aRecomputedWordBoundaryIsAccepted() {
        val request = HostSelectionRequest()
        request.request("notes", 95, 103, 0)
        assertEquals(95 to 104, request.take("notes", 95, 104, 10))
    }

    // 密码框点格子/删字符只是把光标从一处移到另一处（start==end），不能当成「选中文字」建立请求，
    // 否则每点一下都会把输入法重新弹出来。只有真正非空选区才建请求；无效端点（-1）也不建。
    @Test fun caretClickCannotArmButInvalidPositionsCannot() {
        val request = HostSelectionRequest()
        request.request("notes", 12, 12); assertNull(request.take("notes", 12, 12))
        request.request("notes", -1, -1); assertNull(request.take("notes", -1, -1))
    }

    // onStartInput 只有「初始选区」可用，没有旧值可比：空光标不能自己把编辑框弹出来。
    @Test fun onlyNonEmptyInitialSelectionsCanArmTheHiddenEntry() {
        val request = HostSelectionRequest()
        request.fromInitial("notes", 5, 5); assertNull(request.take("notes", 5, 5))
        request.fromInitial("notes", -1, 4); assertNull(request.take("notes", -1, 4))
        request.fromInitial("notes", 4, -1); assertNull(request.take("notes", 4, -1))
        request.fromInitial("notes", 4, 9); assertEquals(4 to 9, request.take("notes", 4, 9))
        request.fromInitial("notes", 9, 4); assertEquals(9 to 4, request.take("notes", 4, 9))
        request.fromInitial("notes", 4, 9); assertNull(request.take("chat", 4, 9))
        request.fromInitial("notes", 4, 9); request.cancel(); assertNull(request.take("notes", 4, 9))
    }
}
