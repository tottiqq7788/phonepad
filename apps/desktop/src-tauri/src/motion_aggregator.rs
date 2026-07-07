#[derive(Debug, Default, Clone, Copy, PartialEq, Eq)]
pub struct MotionAggregator {
    move_dx: i32,
    move_dy: i32,
    scroll_dx: i32,
    scroll_dy: i32,
}

impl MotionAggregator {
    pub fn accumulate_move(&mut self, dx: i16, dy: i16) {
        self.move_dx += i32::from(dx);
        self.move_dy += i32::from(dy);
    }

    pub fn accumulate_scroll(&mut self, dx: i16, dy: i16) {
        self.scroll_dx += i32::from(dx);
        self.scroll_dy += i32::from(dy);
    }

    pub fn take_move(&mut self) -> (i16, i16) {
        let dx = clamp_i16(self.move_dx);
        let dy = clamp_i16(self.move_dy);
        self.move_dx -= i32::from(dx);
        self.move_dy -= i32::from(dy);
        (dx, dy)
    }

    pub fn take_scroll(&mut self) -> (i16, i16) {
        let dx = clamp_i16(self.scroll_dx);
        let dy = clamp_i16(self.scroll_dy);
        self.scroll_dx -= i32::from(dx);
        self.scroll_dy -= i32::from(dy);
        (dx, dy)
    }

    pub fn has_pending(&self) -> bool {
        self.move_dx != 0
            || self.move_dy != 0
            || self.scroll_dx != 0
            || self.scroll_dy != 0
    }

    pub fn clear(&mut self) {
        *self = Self::default();
    }
}

fn clamp_i16(value: i32) -> i16 {
    value.clamp(i16::MIN as i32, i16::MAX as i32) as i16
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn accumulates_multiple_small_moves_before_frame_apply() {
        let mut aggregator = MotionAggregator::default();
        for _ in 0..5 {
            aggregator.accumulate_move(1, 0);
        }
        let (dx, dy) = aggregator.take_move();
        assert_eq!(dx, 5);
        assert_eq!(dy, 0);
        assert!(!aggregator.has_pending());
    }

    #[test]
    fn drains_large_backlog_across_frames() {
        let mut aggregator = MotionAggregator::default();
        aggregator.accumulate_move(i16::MAX, i16::MIN);
        aggregator.accumulate_move(i16::MAX, 0);
        let (first_dx, first_dy) = aggregator.take_move();
        assert_eq!(first_dx, i16::MAX);
        assert_eq!(first_dy, i16::MIN);
        assert!(aggregator.has_pending());
        let (second_dx, second_dy) = aggregator.take_move();
        assert_eq!(second_dx, i16::MAX);
        assert_eq!(second_dy, 0);
        assert!(!aggregator.has_pending());
    }

    #[test]
    fn scroll_and_move_are_independent() {
        let mut aggregator = MotionAggregator::default();
        aggregator.accumulate_move(3, 4);
        aggregator.accumulate_scroll(5, -2);
        assert_eq!(aggregator.take_move(), (3, 4));
        assert_eq!(aggregator.take_scroll(), (5, -2));
    }
}
