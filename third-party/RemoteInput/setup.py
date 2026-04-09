import os
import sys
import subprocess
from setuptools import setup, Extension
from setuptools.command.build_ext import build_ext
from setuptools import find_packages

class CMakeBuild(build_ext):
    def run(self):
        try:
            subprocess.check_output(['cmake', '--version'])
        except OSError:
            raise RuntimeError('CMake must be installed to build RemoteInput')

        for extension in self.extensions:
            self.build_extension(extension)

    def build_extension(self, extension):
        extension_dir = os.path.abspath(os.path.dirname(self.get_ext_fullpath(extension.name)))
        cmake_args = [
            f'-DCMAKE_LIBRARY_OUTPUT_DIRECTORY={extension_dir}',
            f'-DPYTHON_EXECUTABLE={sys.executable}',
            '-DPYTHON_BINDINGS=ON',
            '-DUSE_PYBIND11=ON'
        ]

        build_type = 'Debug' if self.debug else 'Release'
        build_args = ['--config', build_type]

        if sys.platform.startswith('win'):
            cmake_args += [
                f'-DCMAKE_LIBRARY_OUTPUT_DIRECTORY_{build_type.upper()}={extension_dir}',
                '-A', 'x64' if sys.maxsize > 2**32 else 'Win32'
            ]
            build_args += ['--', '/m']
        else:
            cmake_args += [f'-DCMAKE_BUILD_TYPE={build_type}']
            build_args += ['--', '-j2']

        build_temp = self.build_temp
        if not os.path.exists(build_temp):
            os.makedirs(build_temp)

        subprocess.check_call(['cmake', extension.sourcedir] + cmake_args, cwd=build_temp)
        subprocess.check_call(['cmake', '--build', '.'] + build_args, cwd=build_temp)

class CMakeExtension(Extension):
    def __init__(self, name, sourcedir=''):
        super().__init__(name, sources=[])
        self.sourcedir = os.path.abspath(sourcedir)

# Setup function
setup(
    name='RemoteInput',
    version='1.0.0',
    description='Remote Input',
    packages=find_packages(),
    ext_modules=[CMakeExtension('RemoteInput', sourcedir='.')],
    cmdclass={'build_ext': CMakeBuild},
    zip_safe=False,
)