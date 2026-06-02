#!/usr/bin/env python3
import os
import sys
import time
import requests

CAMUNDA_URL = os.getenv('CAMUNDA_URL', 'http:


def get(path):
    return requests.get(f'{CAMUNDA_URL}{path}', timeout=10)


def wait_for_camunda() -> bool:
    for _ in range(60):
        try:
            if get('/version').status_code == 200:
                return True
        except Exception:
            pass
        time.sleep(2)
    return False


def process_deployed(key: str) -> bool:
    resp = get(f'/process-definition?key={key}&latestVersion=true')
    return resp.status_code == 200 and bool(resp.json())


def main() -> int:
    if not wait_for_camunda():
        print(f'Camunda REST is unavailable: {CAMUNDA_URL}')
        return 1

    required = [
        'hhApplicationProcess',
        'hhVacancyProcess',
        'hhTimeoutSchedulerProcess',
    ]
    for key in required:
        for _ in range(60):
            if process_deployed(key):
                print(f'OK process definition deployed: {key}')
                break
            time.sleep(2)
        else:
            print(f'Process definition was not deployed: {key}')
            return 1

    return 0


if __name__ == '__main__':
    sys.exit(main())
