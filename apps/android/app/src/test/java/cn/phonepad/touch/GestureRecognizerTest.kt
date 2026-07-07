package cn.phonepad.touch

import cn.phonepad.protocol.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GestureRecognizerTest {
  private val recognizer = GestureRecognizer()

  @Test
  fun classifiesVerticalSwipeUp() {
    val kind = recognizer.classify(0f, -80f)
    assertEquals(Protocol.GestureKind.SwipeUp, kind)
  }

  @Test
  fun classifiesVerticalSwipeDown() {
    val kind = recognizer.classify(0f, 80f)
    assertEquals(Protocol.GestureKind.SwipeDown, kind)
  }

  @Test
  fun classifiesHorizontalSwipeLeft() {
    val kind = recognizer.classify(-80f, 0f)
    assertEquals(Protocol.GestureKind.SwipeLeft, kind)
  }

  @Test
  fun classifiesHorizontalSwipeRight() {
    val kind = recognizer.classify(80f, 0f)
    assertEquals(Protocol.GestureKind.SwipeRight, kind)
  }

  @Test
  fun ignoresDiagonalSwipeBelowThreshold() {
    recognizer.begin(3, 0f, 0f)
    assertNull(recognizer.update(40f, 40f))
    assertNull(recognizer.end(40f, 40f))
  }

  @Test
  fun endReturnsGestureWhenDistanceExceedsThreshold() {
    recognizer.begin(3, 0f, 0f)
    val event = recognizer.end(0f, 100f)
    assertEquals(Protocol.GestureKind.SwipeDown, event?.kind)
    assertEquals(Protocol.GesturePhase.End, event?.phase)
    assertEquals(3, event?.fingers)
  }

  @Test
  fun fourFingerSwipeLeftIsClassified() {
    recognizer.begin(4, 100f, 100f)
    val event = recognizer.end(10f, 100f)
    assertEquals(Protocol.GestureKind.SwipeLeft, event?.kind)
    assertEquals(4, event?.fingers)
  }
}
