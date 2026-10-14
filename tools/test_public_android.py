import tempfile
from pathlib import Path
import unittest
import zipfile
from check_public_android import find_markers


class PublicIdentityScanTest(unittest.TestCase):
    def test_decoded_dex_resource_and_native_entries_are_all_scanned(self):
        with tempfile.TemporaryDirectory() as folder:
            apk = Path(folder) / 'fixture.apk'
            with zipfile.ZipFile(apk, 'w', zipfile.ZIP_DEFLATED) as z:
                z.writestr('classes.dex', b'https://github.com/PrivateOwner/app')
                z.writestr('resources.arsc', 'PRIVATEOWNER'.encode('utf-16-le'))
                z.writestr('lib/x86_64/libtunnel.so', b'build at /users/privateowner/src')
                z.writestr('assets/notice.html', 'Allowed copyright owner')
            self.assertEqual(find_markers(apk, ['PrivateOwner']),
                             ['classes.dex', 'resources.arsc', 'lib/x86_64/libtunnel.so'])

    def test_utf16_big_endian_and_entry_names_are_scanned(self):
        with tempfile.TemporaryDirectory() as folder:
            apk = Path(folder) / 'fixture.apk'
            with zipfile.ZipFile(apk, 'w') as z:
                z.writestr('safe', 'PrivateOwner'.encode('utf-16-be'))
                z.writestr('PrivateOwner/file', b'otherwise clean')
            self.assertEqual(find_markers(apk, ['PrivateOwner']), ['safe', 'PrivateOwner/file'])

    def test_clean_archive_and_empty_marker_rejection(self):
        with tempfile.TemporaryDirectory() as folder:
            apk = Path(folder) / 'fixture.apk'
            with zipfile.ZipFile(apk, 'w') as z:
                z.writestr('assets/notice', b'Drkozol')
            self.assertEqual(find_markers(apk, ['PrivateOwner']), [])
            with self.assertRaises(ValueError):
                find_markers(apk, [''])


if __name__ == '__main__':
    unittest.main()
