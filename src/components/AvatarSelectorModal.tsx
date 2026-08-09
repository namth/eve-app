import React, { useState } from 'react';
import {
  Modal,
  View,
  Text,
  TouchableOpacity,
  StyleSheet,
  Image,
  ScrollView,
  Alert,
  Platform,
} from 'react-native';
import { AvatarMode, HumanAvatarConfig, PRESET_AVATARS, AvatarPresetId } from '../types/avatar';
import * as ImagePicker from 'expo-image-picker';

interface Props {
  visible: boolean;
  onClose: () => void;
  avatarMode: AvatarMode;
  onSelectMode: (mode: AvatarMode) => void;
  humanConfig: HumanAvatarConfig;
  onUpdateHumanConfig: (config: HumanAvatarConfig) => void;
}

export const AvatarSelectorModal: React.FC<Props> = ({
  visible,
  onClose,
  avatarMode,
  onSelectMode,
  humanConfig,
  onUpdateHumanConfig,
}) => {
  const [selectedMode, setSelectedMode] = useState<AvatarMode>(avatarMode);
  const [selectedConfig, setSelectedConfig] = useState<HumanAvatarConfig>(humanConfig);

  const handlePickCustomImage = async () => {
    try {
      const permissionResult = await ImagePicker.requestMediaLibraryPermissionsAsync();
      if (!permissionResult.granted) {
        Alert.alert('Cần quyền truy cập', 'Vui lòng cấp quyền truy cập thư viện ảnh để chọn ảnh người thật của bạn.');
        return;
      }

      const result = await ImagePicker.launchImageLibraryAsync({
        mediaTypes: ImagePicker.MediaTypeOptions.Images,
        allowsEditing: true,
        aspect: [3, 4],
        quality: 0.8,
        base64: true,
      });

      if (!result.canceled && result.assets && result.assets.length > 0) {
        const asset = result.assets[0];
        const uri = asset.base64 ? `data:image/jpeg;base64,${asset.base64}` : asset.uri;
        
        const newConfig: HumanAvatarConfig = {
          type: 'custom',
          customUri: uri,
        };
        setSelectedConfig(newConfig);
        setSelectedMode('human');
      }
    } catch (error) {
      console.error('Image picker error:', error);
      Alert.alert('Lỗi', 'Không thể nạp ảnh từ thư viện.');
    }
  };

  const handleApply = () => {
    onSelectMode(selectedMode);
    onUpdateHumanConfig(selectedConfig);
    onClose();
  };

  return (
    <Modal visible={visible} animationType="slide" transparent onRequestClose={onClose}>
      <View style={styles.overlay}>
        <View style={styles.container}>
          {/* Header */}
          <View style={styles.header}>
            <Text style={styles.title}>Chọn Giao diện EVE Assistant</Text>
            <TouchableOpacity onPress={onClose} style={styles.closeBtn}>
              <Text style={styles.closeText}>✕</Text>
            </TouchableOpacity>
          </View>

          <ScrollView style={styles.body} showsVerticalScrollIndicator={false}>
            {/* Mode Switcher Tabs */}
            <Text style={styles.sectionLabel}>CHẾ ĐỘ HIỂN THỊ</Text>
            <View style={styles.tabContainer}>
              <TouchableOpacity
                style={[styles.tab, selectedMode === 'robot' && styles.activeTab]}
                onPress={() => setSelectedMode('robot')}
              >
                <Text style={[styles.tabText, selectedMode === 'robot' && styles.activeTabText]}>
                  🤖 Robot EVE
                </Text>
              </TouchableOpacity>

              <TouchableOpacity
                style={[styles.tab, selectedMode === 'human' && styles.activeTab]}
                onPress={() => setSelectedMode('human')}
              >
                <Text style={[styles.tabText, selectedMode === 'human' && styles.activeTabText]}>
                  👩 Người thật Nói chuyện
                </Text>
              </TouchableOpacity>
            </View>

            {/* Human Avatar Section */}
            {selectedMode === 'human' && (
              <View style={styles.humanSection}>
                <Text style={styles.sectionLabel}>MẪU NHÂN VẬT NGƯỜI THẬT</Text>

                {/* Preset Avatars Grid */}
                <View style={styles.presetGrid}>
                  {(Object.keys(PRESET_AVATARS) as AvatarPresetId[]).map((id) => {
                    const preset = PRESET_AVATARS[id];
                    const isSelected = selectedConfig.type === 'preset' && selectedConfig.presetId === id;

                    return (
                      <TouchableOpacity
                        key={id}
                        style={[styles.avatarCard, isSelected && styles.activeCard]}
                        onPress={() => {
                          setSelectedConfig({ type: 'preset', presetId: id });
                        }}
                      >
                        <Image source={preset.imagePath} style={styles.avatarImg} />
                        <View style={styles.cardInfo}>
                          <Text style={styles.avatarName}>{preset.name}</Text>
                          <Text style={styles.avatarDesc}>{preset.description}</Text>
                        </View>
                        {isSelected && <View style={styles.checkBadge}><Text style={styles.checkText}>✓</Text></View>}
                      </TouchableOpacity>
                    );
                  })}
                </View>

                {/* Custom Photo Upload Button */}
                <Text style={styles.sectionLabel}>ẢNH TÙY CHỈNH CỦA BẠN</Text>
                <TouchableOpacity style={styles.uploadCard} onPress={handlePickCustomImage}>
                  {selectedConfig.type === 'custom' && selectedConfig.customUri ? (
                    <Image source={{ uri: selectedConfig.customUri }} style={styles.customPreviewImg} />
                  ) : (
                    <View style={styles.uploadPlaceholder}>
                      <Text style={styles.uploadIcon}>📷</Text>
                      <Text style={styles.uploadTitle}>Tải ảnh người thật từ máy</Text>
                      <Text style={styles.uploadSubtitle}>Hỗ trợ JPG, PNG chất lượng cao</Text>
                    </View>
                  )}
                </TouchableOpacity>
              </View>
            )}
          </ScrollView>

          {/* Footer Action Button */}
          <View style={styles.footer}>
            <TouchableOpacity style={styles.applyBtn} onPress={handleApply}>
              <Text style={styles.applyText}>Áp dụng Giao diện</Text>
            </TouchableOpacity>
          </View>
        </View>
      </View>
    </Modal>
  );
};

const styles = StyleSheet.create({
  overlay: {
    flex: 1,
    backgroundColor: 'rgba(3, 7, 18, 0.75)',
    justifyContent: 'flex-end',
  },
  container: {
    backgroundColor: '#0b0f19',
    borderTopLeftRadius: 28,
    borderTopRightRadius: 28,
    borderWidth: 1,
    borderColor: 'rgba(0, 240, 255, 0.25)',
    maxHeight: '85%',
    paddingBottom: Platform.OS === 'ios' ? 24 : 16,
  },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 20,
    paddingVertical: 18,
    borderBottomWidth: 1,
    borderBottomColor: 'rgba(255, 255, 255, 0.08)',
  },
  title: {
    fontSize: 16,
    fontWeight: '700',
    color: '#ffffff',
    letterSpacing: 0.5,
  },
  closeBtn: {
    padding: 6,
  },
  closeText: {
    fontSize: 18,
    color: '#94a3b8',
    fontWeight: '600',
  },
  body: {
    paddingHorizontal: 20,
    paddingTop: 16,
  },
  sectionLabel: {
    fontSize: 11,
    fontWeight: '700',
    color: '#00f0ff',
    letterSpacing: 1.2,
    marginBottom: 10,
    marginTop: 8,
  },
  tabContainer: {
    flexDirection: 'row',
    backgroundColor: 'rgba(15, 23, 42, 0.8)',
    borderRadius: 14,
    padding: 4,
    borderWidth: 1,
    borderColor: 'rgba(255, 255, 255, 0.08)',
    marginBottom: 16,
  },
  tab: {
    flex: 1,
    paddingVertical: 12,
    alignItems: 'center',
    borderRadius: 10,
  },
  activeTab: {
    backgroundColor: '#00f0ff',
    shadowColor: '#00f0ff',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.4,
    shadowRadius: 6,
    elevation: 4,
  },
  tabText: {
    fontSize: 13,
    fontWeight: '600',
    color: '#94a3b8',
  },
  activeTabText: {
    color: '#030712',
    fontWeight: '700',
  },
  humanSection: {
    marginTop: 4,
  },
  presetGrid: {
    gap: 12,
    marginBottom: 16,
  },
  avatarCard: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: 'rgba(15, 23, 42, 0.7)',
    borderRadius: 16,
    padding: 10,
    borderWidth: 1.5,
    borderColor: 'rgba(255, 255, 255, 0.08)',
    position: 'relative',
  },
  activeCard: {
    borderColor: '#00f0ff',
    backgroundColor: 'rgba(0, 240, 255, 0.08)',
  },
  avatarImg: {
    width: 60,
    height: 60,
    borderRadius: 12,
  },
  cardInfo: {
    flex: 1,
    marginLeft: 14,
  },
  avatarName: {
    fontSize: 14,
    fontWeight: '700',
    color: '#ffffff',
    marginBottom: 4,
  },
  avatarDesc: {
    fontSize: 11,
    color: '#94a3b8',
  },
  checkBadge: {
    width: 24,
    height: 24,
    borderRadius: 12,
    backgroundColor: '#00f0ff',
    alignItems: 'center',
    justifyContent: 'center',
    marginRight: 6,
  },
  checkText: {
    fontSize: 14,
    fontWeight: '800',
    color: '#030712',
  },
  uploadCard: {
    height: 100,
    borderRadius: 16,
    borderWidth: 1.5,
    borderColor: 'rgba(0, 240, 255, 0.4)',
    borderStyle: 'dashed',
    backgroundColor: 'rgba(15, 23, 42, 0.5)',
    alignItems: 'center',
    justifyContent: 'center',
    overflow: 'hidden',
    marginBottom: 20,
  },
  uploadPlaceholder: {
    alignItems: 'center',
  },
  uploadIcon: {
    fontSize: 24,
    marginBottom: 4,
  },
  uploadTitle: {
    fontSize: 13,
    fontWeight: '600',
    color: '#ffffff',
  },
  uploadSubtitle: {
    fontSize: 10,
    color: '#64748b',
    marginTop: 2,
  },
  customPreviewImg: {
    width: '100%',
    height: '100%',
    resizeMode: 'cover',
  },
  footer: {
    paddingHorizontal: 20,
    paddingTop: 12,
  },
  applyBtn: {
    backgroundColor: '#00f0ff',
    borderRadius: 16,
    paddingVertical: 14,
    alignItems: 'center',
    shadowColor: '#00f0ff',
    shadowOffset: { width: 0, height: 4 },
    shadowOpacity: 0.35,
    shadowRadius: 10,
    elevation: 6,
  },
  applyText: {
    fontSize: 15,
    fontWeight: '700',
    color: '#030712',
    letterSpacing: 0.5,
  },
});
