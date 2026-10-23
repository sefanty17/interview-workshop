"""DeepTutor launcher with a clean NO_PROXY.

The host environment injects NO_PROXY containing "[::1]", which httpx (used by
DeepTutor's update checker and provider clients) parses as an invalid port.
Setting it inside Python before deeptutor imports httpx avoids that.
"""
import os
import sys

os.environ.pop("no_proxy", None)
os.environ["NO_PROXY"] = "api.deepseek.com,localhost,127.0.0.1,::1"

HOME = r"D:\cxdownload\javaee课设\deeptutor-runtime"
sys.argv = ["deeptutor", "start", "--no-browser", "--home", HOME]

from deeptutor_cli.main import main  # noqa: E402

main()
