import os
import os.path as osp
from typing import Optional
from . import helpers
from .. import models
from ..models import User
from ....deep import thing

MAX_RETRIES = 3
DEFAULT_HOST, DEFAULT_PORT = "localhost", 8080


@dataclass
class Config:
    host: str = DEFAULT_HOST
    port: int = DEFAULT_PORT


class Service:
    name: str
    _cache = {}

    def __init__(self, name):
        self.name = name

    def save(self):
        return self.name

    async def fetch(self):
        return await self._load()

    @staticmethod
    def helper():
        return True

    class Inner:
        def act(self):
            return "acted"


async def process(items):
    def transform(item):
        return item

    return [transform(i) for i in items]


@retry(times=3)
def flaky():
    return True
