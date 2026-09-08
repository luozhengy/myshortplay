import 'dart:io';

import 'package:dio/dio.dart';
import 'package:flutter/services.dart';
import 'package:path_provider/path_provider.dart';

import 'crypto_native_channel.dart';

/// 视频保存服务 - 负责将当前正在播放的视频保存到本地
/// 将当前正在播放的视频保存到应用文档目录。
class VideoSaveService {
  static final Dio _dio = Dio();
  static const MethodChannel _downloadsChannel =
      MethodChannel('shortplay/downloads');

  /// 保存当前正在播放的视频到本地文件
  ///
  /// [cdnUrl]: CDN视频地址
  /// [keyHex]: 加密密钥（32位十六进制字符串），为空表示未加密
  /// [dramaName]: 剧名
  /// [episodeName]: 集名（如："第1集" 或 "1"）
  ///
  /// 普通视频由 Dart 下载；加密视频由现有 crypto 通道解密到文件。
  /// 返回保存成功的文件路径，失败返回 null。
  static Future<String?> saveVideo({
    required String cdnUrl,
    required String keyHex,
    required String dramaName,
    required String episodeName,
  }) async {
    try {
      final directory = Directory(await getSaveDirectory());
      if (!directory.existsSync()) await directory.create(recursive: true);
      final fileName = '${_safeName(dramaName)}_${_safeName(episodeName)}.mp4';
      final outputPath = '${directory.path}/$fileName';
      final outputFile = File(outputPath);

      if (keyHex.isNotEmpty) {
        final status = await CryptoNativeChannel.instance.decryptToFile(
          cdnUrl,
          keyHex,
          outputPath,
        );
        if (status != 0) return null;
      } else if (cdnUrl.startsWith('file://')) {
        await File.fromUri(Uri.parse(cdnUrl)).copy(outputPath);
      } else if (File(cdnUrl).existsSync()) {
        await File(cdnUrl).copy(outputPath);
      } else {
        await _dio.download(cdnUrl, outputPath);
      }
      if (!outputFile.existsSync()) return null;

      // Keep the Dart-managed copy, then publish another copy to Android Downloads.
      return await publishFileToDownloads(
        sourcePath: outputPath,
        dramaName: dramaName,
        episodeName: episodeName,
      );
    } catch (e) {
      print('保存视频失败: $e');
      return null;
    }
  }

  /// 将已下载到应用目录的视频复制到系统公共 Download 目录。
  static Future<String?> decryptToDownloads({
    required String cdnUrl,
    required String keyHex,
    required String dramaName,
    required String episodeName,
  }) async {
    if (!Platform.isAndroid) return null;
    try {
      return await _downloadsChannel.invokeMethod<String>(
        'decryptToDownloads',
        {
          'url': cdnUrl,
          'key': keyHex,
          'displayName':
              '${_safeName(dramaName)}_${_safeName(episodeName)}.mp4',
        },
      );
    } on PlatformException catch (e) {
      print('直接解密到手机下载目录失败: ${e.message}');
      return null;
    }
  }

  /// 将已下载到应用目录的视频复制到系统公共 Download 目录。
  static Future<String?> publishFileToDownloads({
    required String sourcePath,
    required String dramaName,
    required String episodeName,
  }) async {
    if (!Platform.isAndroid) return sourcePath;
    try {
      final publishedPath = await _downloadsChannel.invokeMethod<String>(
        'publishToDownloads',
        {
          'sourcePath': sourcePath,
          'displayName':
              '${_safeName(dramaName)}_${_safeName(episodeName)}.mp4',
        },
      );
      return publishedPath ?? sourcePath;
    } on PlatformException catch (e) {
      print('发布到手机下载目录失败: ${e.message}');
      return null;
    }
  }

  /// 删除已发布到系统 Download 目录的视频。
  static Future<bool> deletePublishedFile(String filePath) async {
    if (!filePath.startsWith('content://')) {
      final file = File(filePath);
      if (!await file.exists()) return false;
      await file.delete();
      return true;
    }
    try {
      return await _downloadsChannel.invokeMethod<bool>(
            'deleteFromDownloads',
            {'uri': filePath},
          ) ??
          false;
    } on PlatformException catch (e) {
      print('删除公共下载文件失败: ${e.message}');
      return false;
    }
  }

  /// 获取已保存的视频列表
  static Future<List<String>> getSavedVideos() async {
    try {
      final directory = Directory(await getSaveDirectory());
      if (!directory.existsSync()) return [];
      final files = await directory
          .list()
          .where((entity) => entity is File && entity.path.endsWith('.mp4'))
          .map((entity) => entity.path)
          .toList();
      files.sort();
      return files;
    } catch (e) {
      print('获取保存的视频列表失败: $e');
      return [];
    }
  }

  /// 删除已保存的视频
  static Future<bool> deleteVideo(String filePath) async {
    try {
      final file = File(filePath);
      if (!await file.exists()) return false;
      await file.delete();
      return true;
    } catch (e) {
      print('删除视频失败: $e');
      return false;
    }
  }

  /// 获取视频文件大小（字节）
  static Future<int> getVideoFileSize(String filePath) async {
    try {
      final file = File(filePath);
      if (!await file.exists()) return 0;
      return await file.length();
    } catch (e) {
      print('获取视频文件大小失败: $e');
      return 0;
    }
  }

  /// 获取保存视频的目录路径
  static Future<String> getSaveDirectory() async {
    final appDir = await getApplicationDocumentsDirectory();
    return '${appDir.path}/SavedVideos';
  }

  /// 获取格式化的文件大小字符串
  static String formatFileSize(int bytes) {
    if (bytes <= 0) return "0 B";
    const suffixes = ["B", "KB", "MB", "GB", "TB"];
    var i = 0;
    var value = bytes.toDouble();
    while (value >= 1024 && i < suffixes.length - 1) {
      value /= 1024;
      i++;
    }
    return '${value.toStringAsFixed(2)} ${suffixes[i]}';
  }

  static String _safeName(String value) {
    final name = value.trim().replaceAll(RegExp(r'[\\/:*?"<>|]'), '_');
    return name.isEmpty ? 'video' : name;
  }
}
