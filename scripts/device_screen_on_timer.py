"""Current screen-driven stopwatch regression; original run history remains append-only."""
from device_timer_lifecycle import screen_behavior, effective_millis

def screen_on(d):
    return screen_behavior(d)
