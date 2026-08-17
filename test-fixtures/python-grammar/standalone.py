from . import sibling
from .. import toohigh

TOP_CONST = 42


def make_adder(x):
    def adder(y):
        return x + y
    return adder


@decorator
def decorated_top():
    pass
