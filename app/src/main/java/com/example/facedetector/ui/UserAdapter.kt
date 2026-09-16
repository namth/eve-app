package com.example.facedetector.ui

import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.facedetector.R
import com.example.facedetector.data.PersonProfile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class UserAdapter(
    private var userList: List<PersonProfile>,
    private val onEditClick: (PersonProfile) -> Unit,
    private val onDeleteClick: (PersonProfile) -> Unit
) : RecyclerView.Adapter<UserAdapter.UserViewHolder>() {

    class UserViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val ivAvatar: ImageView = itemView.findViewById(R.id.ivUserAvatar)
        val tvName: TextView = itemView.findViewById(R.id.tvUserName)
        val tvRoleBadge: TextView = itemView.findViewById(R.id.tvUserRoleBadge)
        val tvMeta: TextView = itemView.findViewById(R.id.tvUserMeta)
        val tvLastSeen: TextView = itemView.findViewById(R.id.tvUserLastSeen)
        val btnEdit: ImageButton = itemView.findViewById(R.id.btnEditUser)
        val btnDelete: ImageButton = itemView.findViewById(R.id.btnDeleteUser)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): UserViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_user_profile, parent, false)
        return UserViewHolder(view)
    }

    override fun onBindViewHolder(holder: UserViewHolder, position: Int) {
        val person = userList[position]

        // 1. Tên & Xưng hô
        holder.tvName.text = "${person.preferredPronoun} ${person.name}"

        // 2. Huy hiệu vai trò
        if (person.role.equals("admin", ignoreCase = true)) {
            holder.tvRoleBadge.text = "ADMIN"
            holder.tvRoleBadge.setTextColor(Color.parseColor("#00F0FF"))
            holder.tvRoleBadge.setBackgroundColor(Color.parseColor("#083344"))
        } else {
            holder.tvRoleBadge.text = "BẠN BÈ"
            holder.tvRoleBadge.setTextColor(Color.parseColor("#34D399"))
            holder.tvRoleBadge.setBackgroundColor(Color.parseColor("#064E3B"))
        }

        // 3. Thông tin chi tiết (Giới tính, Tuổi)
        val genderStr = when (person.gender.lowercase()) {
            "male" -> "Nam"
            "female" -> "Nữ"
            else -> "Khác"
        }
        val ageStr = if (person.age != null) "${person.age} tuổi" else "Chưa rõ tuổi"
        val faceGoc = if (person.faceEmbeddings.isNotEmpty()) "${person.faceEmbeddings.size}/9 góc" else "Chưa có mặt"
        holder.tvMeta.text = "$genderStr • $ageStr • $faceGoc • Xưng: ${person.preferredPronoun}"

        // 4. Thời gian gặp gần nhất
        val dateStr = if (person.lastSeenAt > 0) {
            val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
            "Gặp gần nhất: " + sdf.format(Date(person.lastSeenAt))
        } else {
            "Chưa từng gặp mặt"
        }
        holder.tvLastSeen.text = dateStr

        // 5. Ảnh đại diện Avatar (từ avatarBase64 hoặc icon mặc định)
        if (!person.avatarBase64.isNullOrEmpty()) {
            try {
                val cleanBase64 = if (person.avatarBase64.contains(",")) {
                    person.avatarBase64.substringAfter(",")
                } else {
                    person.avatarBase64
                }
                val decodedBytes = Base64.decode(cleanBase64, Base64.DEFAULT)
                val bitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
                if (bitmap != null) {
                    holder.ivAvatar.setImageBitmap(bitmap)
                } else {
                    holder.ivAvatar.setImageResource(R.mipmap.ic_launcher_round)
                }
            } catch (e: Exception) {
                holder.ivAvatar.setImageResource(R.mipmap.ic_launcher_round)
            }
        } else {
            holder.ivAvatar.setImageResource(R.mipmap.ic_launcher_round)
        }

        // 6. Sự kiện Sửa / Xóa
        holder.btnEdit.setOnClickListener { onEditClick(person) }
        holder.btnDelete.setOnClickListener { onDeleteClick(person) }
    }

    override fun getItemCount(): Int = userList.size

    fun updateData(newList: List<PersonProfile>) {
        userList = newList
        notifyDataSetChanged()
    }
}
