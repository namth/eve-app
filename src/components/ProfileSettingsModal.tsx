import React, { useState, useEffect } from 'react';
import {
  Modal,
  View,
  Text,
  TouchableOpacity,
  FlatList,
  TextInput,
  StyleSheet,
  Alert,
  ScrollView,
} from 'react-native';
import { PersonProfile, UserRole } from '../types/personProfile';
import { peopleDatabaseService } from '../services/peopleDatabaseService';

interface ProfileSettingsModalProps {
  visible: boolean;
  onClose: () => void;
  currentPerson: PersonProfile | null;
  onSelectPerson: (person: PersonProfile) => void;
  onTriggerScan?: () => void;
}

export const ProfileSettingsModal: React.FC<ProfileSettingsModalProps> = ({
  visible,
  onClose,
  currentPerson,
  onSelectPerson,
  onTriggerScan,
}) => {
  const [people, setPeople] = useState<PersonProfile[]>([]);
  const [editingPerson, setEditingPerson] = useState<PersonProfile | null>(null);

  useEffect(() => {
    if (visible) {
      loadPeople();
    }
  }, [visible]);

  const loadPeople = async () => {
    const list = await peopleDatabaseService.getPeopleList();
    setPeople(list);
  };

  const handleSaveEdit = async () => {
    if (!editingPerson) return;
    if (!editingPerson.name.trim()) {
      Alert.alert('Lỗi', 'Vui lòng nhập tên người dùng');
      return;
    }
    await peopleDatabaseService.savePersonProfile(editingPerson);
    Alert.alert('Thành công', `Đã cập nhật thông tin cho ${editingPerson.name}`);
    setEditingPerson(null);
    loadPeople();
  };

  const handleToggleRole = async (person: PersonProfile) => {
    const newRole: UserRole = person.role === 'admin' ? 'friend' : 'admin';
    await peopleDatabaseService.updatePersonRole(person.id, newRole);
    if (currentPerson?.id === person.id) {
      onSelectPerson({ ...person, role: newRole });
    }
    loadPeople();
  };

  const handleDeletePerson = async (personId: string, personName: string) => {
    Alert.alert(
      'Xác nhận xóa',
      `Bạn có chắc chắn muốn xóa ${personName} khỏi danh bạ người quen?`,
      [
        { text: 'Hủy', style: 'cancel' },
        {
          text: 'Xóa',
          style: 'destructive',
          onPress: async () => {
            await peopleDatabaseService.deletePersonProfile(personId);
            if (editingPerson?.id === personId) {
              setEditingPerson(null);
            }
            loadPeople();
          },
        },
      ]
    );
  };

  return (
    <Modal visible={visible} animationType="slide" transparent={true} onRequestClose={onClose}>
      <View style={styles.overlay}>
        <View style={styles.container}>
          {/* Header */}
          <View style={styles.header}>
            <Text style={styles.headerTitle}>⚙️ Cài đặt Profile & Phân quyền</Text>
            <TouchableOpacity onPress={onClose} style={styles.closeButton}>
              <Text style={styles.closeText}>✕</Text>
            </TouchableOpacity>
          </View>

          {/* Current Recognized Status */}
          <View style={styles.activeCard}>
            <Text style={styles.activeSubtitle}>ĐANG TƯƠNG TÁC TRỰC TIẾP</Text>
            <View style={styles.activeRow}>
              <View style={styles.avatarCircle}>
                <Text style={styles.avatarText}>
                  {currentPerson?.name ? currentPerson.name.charAt(0).toUpperCase() : '?'}
                </Text>
              </View>
              <View style={{ flex: 1, marginLeft: 12 }}>
                <Text style={styles.activeName}>
                  {currentPerson ? `${currentPerson.preferred_pronoun} ${currentPerson.name}` : 'Người lạ (Chưa nhận diện)'}
                </Text>
                <Text style={styles.activeRole}>
                  Vai trò: <Text style={currentPerson?.role === 'admin' ? styles.roleAdmin : styles.roleFriend}>
                    {currentPerson ? currentPerson.role.toUpperCase() : 'UNKNOWN'}
                  </Text>
                </Text>
              </View>

              {onTriggerScan && (
                <TouchableOpacity style={styles.scanButton} onPress={() => { onClose(); onTriggerScan?.(); }}>
                  <Text style={styles.scanButtonText}>🔍 Quét lại</Text>
                </TouchableOpacity>
              )}
            </View>
          </View>

          {/* Editing Form or List (ADMIN ONLY) */}
          {currentPerson?.role !== 'admin' ? (
            <View style={styles.restrictedContainer}>
              <Text style={styles.restrictedIcon}>🔒</Text>
              <Text style={styles.restrictedTitle}>GIỚI HẠN QUYỀN TRUY CẬP</Text>
              <Text style={styles.restrictedText}>
                Chỉ người dùng có quyền ADMIN mới được phép xem và quản lý Danh sách Người quen / Phân quyền hệ thống.
              </Text>
              <Text style={styles.restrictedSubText}>
                Hiện tại bạn đang tương tác dưới dạng: {currentPerson ? `${currentPerson.name} (${currentPerson.role.toUpperCase()})` : 'Guest (Người lạ)'}.
              </Text>
            </View>
          ) : editingPerson ? (
            <ScrollView style={styles.editForm}>
              <Text style={styles.sectionTitle}>Sửa Thông Tin: {editingPerson.name}</Text>

              <Text style={styles.inputLabel}>Tên người dùng:</Text>
              <TextInput
                style={styles.textInput}
                value={editingPerson.name}
                onChangeText={(val) => setEditingPerson({ ...editingPerson, name: val })}
                placeholder="Nhập tên..."
                placeholderTextColor="#666"
              />

              <Text style={styles.inputLabel}>Cách EVE xưng hô (Pronoun):</Text>
              <View style={styles.pronounContainer}>
                {['Anh', 'Chị', 'Chú', 'Cô', 'Bạn'].map((p) => (
                  <TouchableOpacity
                    key={p}
                    style={[
                      styles.pronounChip,
                      editingPerson.preferred_pronoun === p && styles.pronounChipActive,
                    ]}
                    onPress={() => setEditingPerson({ ...editingPerson, preferred_pronoun: p })}
                  >
                    <Text
                      style={[
                        styles.pronounChipText,
                        editingPerson.preferred_pronoun === p && styles.pronounChipTextActive,
                      ]}
                    >
                      {p}
                    </Text>
                  </TouchableOpacity>
                ))}
              </View>

              <Text style={styles.inputLabel}>Tuổi (tùy chọn):</Text>
              <TextInput
                style={styles.textInput}
                value={editingPerson.age ? String(editingPerson.age) : ''}
                keyboardType="numeric"
                onChangeText={(val) =>
                  setEditingPerson({ ...editingPerson, age: val ? parseInt(val, 10) : undefined })
                }
                placeholder="Nhập tuổi..."
                placeholderTextColor="#666"
              />

              <Text style={styles.inputLabel}>Phân quyền System Role:</Text>
              <View style={styles.roleToggleRow}>
                <TouchableOpacity
                  style={[
                    styles.roleOption,
                    editingPerson.role === 'friend' && styles.roleOptionActiveFriend,
                  ]}
                  onPress={() => setEditingPerson({ ...editingPerson, role: 'friend' })}
                >
                  <Text style={styles.roleOptionText}>FRIEND (Bạn)</Text>
                </TouchableOpacity>

                <TouchableOpacity
                  style={[
                    styles.roleOption,
                    editingPerson.role === 'admin' && styles.roleOptionActiveAdmin,
                  ]}
                  onPress={() => setEditingPerson({ ...editingPerson, role: 'admin' })}
                >
                  <Text style={styles.roleOptionText}>ADMIN (Quản trị)</Text>
                </TouchableOpacity>
              </View>
              <Text style={styles.roleDesc}>
                * Note: Chỉ người có quyền ADMIN mới nghe được Push Notification bảo mật khi mở app.
              </Text>

              <View style={styles.editActionRow}>
                <TouchableOpacity style={styles.cancelBtn} onPress={() => setEditingPerson(null)}>
                  <Text style={styles.cancelBtnText}>Hủy</Text>
                </TouchableOpacity>
                <TouchableOpacity style={styles.saveBtn} onPress={handleSaveEdit}>
                  <Text style={styles.saveBtnText}>Lưu thay đổi</Text>
                </TouchableOpacity>
              </View>
            </ScrollView>
          ) : (
            <View style={{ flex: 1 }}>
              <Text style={styles.sectionTitle}>Danh Sách Người Quen ({people.length})</Text>
              {people.length === 0 ? (
                <View style={styles.emptyState}>
                  <Text style={styles.emptyText}>Chưa có người dùng nào trong CSDL local.</Text>
                </View>
              ) : (
                <FlatList
                  data={people}
                  keyExtractor={(item) => item.id}
                  renderItem={({ item }) => (
                    <View style={styles.personCard}>
                      <TouchableOpacity
                        style={{ flex: 1 }}
                        onPress={() => {
                          onSelectPerson(item);
                          onClose();
                        }}
                      >
                        <View style={{ flexDirection: 'row', alignItems: 'center' }}>
                          <Text style={styles.personName}>
                            {item.preferred_pronoun} {item.name}
                          </Text>
                          <TouchableOpacity
                            style={[
                              styles.badgeRole,
                              item.role === 'admin' ? styles.badgeAdmin : styles.badgeFriend,
                            ]}
                            onPress={() => handleToggleRole(item)}
                          >
                            <Text style={styles.badgeRoleText}>{item.role.toUpperCase()}</Text>
                          </TouchableOpacity>
                        </View>
                        <Text style={styles.personSub}>
                          Giới tính: {item.gender === 'male' ? 'Nam' : 'Nữ'} • Tuổi: {item.age || 'Chưa rõ'}
                        </Text>
                      </TouchableOpacity>

                      <View style={{ flexDirection: 'row', alignItems: 'center' }}>
                        <TouchableOpacity
                          style={styles.actionIconButton}
                          onPress={() => setEditingPerson(item)}
                        >
                          <Text style={{ color: '#00f0ff', fontSize: 16 }}>✏️</Text>
                        </TouchableOpacity>
                        <TouchableOpacity
                          style={styles.actionIconButton}
                          onPress={() => handleDeletePerson(item.id, item.name)}
                        >
                          <Text style={{ color: '#ff4444', fontSize: 16 }}>🗑️</Text>
                        </TouchableOpacity>
                      </View>
                    </View>
                  )}
                />
              )}
            </View>
          )}
        </View>
      </View>
    </Modal>
  );
};

const styles = StyleSheet.create({
  overlay: {
    flex: 1,
    backgroundColor: 'rgba(0, 0, 0, 0.75)',
    justifyContent: 'center',
    padding: 16,
  },
  container: {
    backgroundColor: '#121824',
    borderRadius: 16,
    borderColor: '#00f0ff33',
    borderWidth: 1,
    padding: 20,
    maxHeight: '85%',
    flex: 1,
  },
  header: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 16,
  },
  headerTitle: {
    color: '#00f0ff',
    fontSize: 18,
    fontWeight: 'bold',
  },
  closeButton: {
    padding: 6,
  },
  closeText: {
    color: '#aaa',
    fontSize: 20,
  },
  activeCard: {
    backgroundColor: '#1a2336',
    borderRadius: 12,
    padding: 14,
    borderColor: '#00f0ff66',
    borderWidth: 1,
    marginBottom: 16,
  },
  activeSubtitle: {
    color: '#00f0ff99',
    fontSize: 10,
    fontWeight: 'bold',
    letterSpacing: 1,
    marginBottom: 6,
  },
  activeRow: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  avatarCircle: {
    width: 40,
    height: 40,
    borderRadius: 20,
    backgroundColor: '#00f0ff22',
    borderColor: '#00f0ff',
    borderWidth: 1,
    justifyContent: 'center',
    alignItems: 'center',
  },
  avatarText: {
    color: '#00f0ff',
    fontWeight: 'bold',
    fontSize: 18,
  },
  activeName: {
    color: '#fff',
    fontSize: 16,
    fontWeight: 'bold',
  },
  activeRole: {
    color: '#aaa',
    fontSize: 12,
    marginTop: 2,
  },
  roleAdmin: {
    color: '#00f0ff',
    fontWeight: 'bold',
  },
  roleFriend: {
    color: '#ffbb00',
    fontWeight: 'bold',
  },
  scanButton: {
    backgroundColor: '#00f0ff22',
    borderColor: '#00f0ff',
    borderWidth: 1,
    paddingVertical: 6,
    paddingHorizontal: 12,
    borderRadius: 20,
  },
  scanButtonText: {
    color: '#00f0ff',
    fontSize: 12,
    fontWeight: 'bold',
  },
  sectionTitle: {
    color: '#fff',
    fontSize: 14,
    fontWeight: 'bold',
    marginBottom: 12,
  },
  personCard: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: '#1a2232',
    padding: 12,
    borderRadius: 10,
    marginBottom: 10,
    borderColor: '#ffffff15',
    borderWidth: 1,
  },
  personName: {
    color: '#fff',
    fontSize: 15,
    fontWeight: 'bold',
    marginRight: 8,
  },
  personSub: {
    color: '#888',
    fontSize: 12,
    marginTop: 4,
  },
  badgeRole: {
    paddingHorizontal: 8,
    paddingVertical: 2,
    borderRadius: 10,
  },
  badgeAdmin: {
    backgroundColor: '#00f0ff33',
    borderColor: '#00f0ff',
    borderWidth: 1,
  },
  badgeFriend: {
    backgroundColor: '#ffffff15',
    borderColor: '#666',
    borderWidth: 1,
  },
  badgeRoleText: {
    color: '#fff',
    fontSize: 10,
    fontWeight: 'bold',
  },
  actionIconButton: {
    padding: 8,
    marginLeft: 4,
  },
  emptyState: {
    padding: 30,
    alignItems: 'center',
  },
  emptyText: {
    color: '#666',
    fontSize: 14,
  },
  editForm: {
    flex: 1,
  },
  inputLabel: {
    color: '#aaa',
    fontSize: 12,
    marginBottom: 6,
    marginTop: 10,
  },
  textInput: {
    backgroundColor: '#1e283a',
    color: '#fff',
    borderRadius: 8,
    padding: 10,
    borderColor: '#334155',
    borderWidth: 1,
    fontSize: 14,
  },
  pronounContainer: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 8,
  },
  pronounChip: {
    backgroundColor: '#1e283a',
    paddingVertical: 6,
    paddingHorizontal: 14,
    borderRadius: 16,
    borderColor: '#334155',
    borderWidth: 1,
  },
  pronounChipActive: {
    backgroundColor: '#00f0ff22',
    borderColor: '#00f0ff',
  },
  pronounChipText: {
    color: '#888',
    fontSize: 13,
  },
  pronounChipTextActive: {
    color: '#00f0ff',
    fontWeight: 'bold',
  },
  roleToggleRow: {
    flexDirection: 'row',
    gap: 10,
  },
  roleOption: {
    flex: 1,
    backgroundColor: '#1e283a',
    paddingVertical: 10,
    borderRadius: 8,
    alignItems: 'center',
    borderColor: '#334155',
    borderWidth: 1,
  },
  roleOptionActiveFriend: {
    backgroundColor: '#ffbb0022',
    borderColor: '#ffbb00',
  },
  roleOptionActiveAdmin: {
    backgroundColor: '#00f0ff22',
    borderColor: '#00f0ff',
  },
  roleOptionText: {
    color: '#fff',
    fontSize: 12,
    fontWeight: 'bold',
  },
  roleDesc: {
    color: '#888',
    fontSize: 11,
    fontStyle: 'italic',
    marginTop: 6,
  },
  editActionRow: {
    flexDirection: 'row',
    gap: 12,
    marginTop: 20,
    marginBottom: 10,
  },
  cancelBtn: {
    flex: 1,
    backgroundColor: '#334155',
    paddingVertical: 12,
    borderRadius: 8,
    alignItems: 'center',
  },
  cancelBtnText: {
    color: '#aaa',
    fontWeight: 'bold',
  },
  saveBtn: {
    flex: 1,
    backgroundColor: '#00f0ff',
    paddingVertical: 12,
    borderRadius: 8,
    alignItems: 'center',
  },
  saveBtnText: {
    color: '#000',
    fontWeight: 'bold',
  },
  restrictedContainer: {
    flex: 1,
    backgroundColor: '#1e293b55',
    borderRadius: 12,
    borderColor: '#334155',
    borderWidth: 1,
    padding: 24,
    alignItems: 'center',
    justifyContent: 'center',
    marginTop: 10,
  },
  restrictedIcon: {
    fontSize: 42,
    marginBottom: 12,
  },
  restrictedTitle: {
    color: '#f43f5e',
    fontSize: 15,
    fontWeight: 'bold',
    letterSpacing: 1,
    marginBottom: 8,
  },
  restrictedText: {
    color: '#cbd5e1',
    fontSize: 13,
    textAlign: 'center',
    lineHeight: 20,
    marginBottom: 12,
  },
  restrictedSubText: {
    color: '#64748b',
    fontSize: 12,
    fontStyle: 'italic',
    textAlign: 'center',
  },
});
