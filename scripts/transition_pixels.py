"""Shared strict transition marker checks used by independent export and UI checks."""
from PIL import ImageStat

def sample(image, x, y):
    sx, sy = image.width / 1280, image.height / 720
    region = image.crop((round((x+12)*sx), round((y+10)*sy), round((x+36)*sx), round((y+30)*sy)))
    return sum(ImageStat.Stat(region).mean) / 3

def identity(image, lane, weight):
    y = 72 + lane * 64
    pilot = sample(image, 8, y)
    if weight <= 4/255:
        assert pilot < 8, ("inactive source visible", lane, pilot)
        return None
    # Marker contrast scales with alpha. Near the last overlap frame the
    # outgoing pilot is only ~8 code values: retain that uncertainty explicitly.
    assert abs(pilot - 255*weight) < 8, ("alpha/pilot", lane, pilot, weight)
    assert pilot > 4, ("unreadable pilot", lane, pilot)
    bits = [sample(image, 72 + bit*64, y) for bit in range(8)]
    assert all(min(abs(v), abs(v-pilot)) < max(3, pilot*.25) for v in bits), ("ambiguous marker", bits, pilot)
    return sum((1 << bit) for bit, v in enumerate(bits) if v > pilot*.5)

