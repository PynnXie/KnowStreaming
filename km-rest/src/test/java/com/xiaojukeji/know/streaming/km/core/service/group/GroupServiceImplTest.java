package com.xiaojukeji.know.streaming.km.core.service.group;

import com.xiaojukeji.know.streaming.km.common.bean.entity.group.Group;
import com.xiaojukeji.know.streaming.km.common.bean.entity.group.GroupTopicMember;
import com.xiaojukeji.know.streaming.km.common.bean.po.group.GroupMemberPO;
import com.xiaojukeji.know.streaming.km.common.bean.po.group.GroupPO;
import com.xiaojukeji.know.streaming.km.common.converter.GroupConverter;
import com.xiaojukeji.know.streaming.km.common.enums.group.GroupStateEnum;
import com.xiaojukeji.know.streaming.km.common.enums.group.GroupTypeEnum;
import com.xiaojukeji.know.streaming.km.core.service.group.impl.GroupServiceImpl;
import com.xiaojukeji.know.streaming.km.persistence.mysql.group.GroupDAO;
import com.xiaojukeji.know.streaming.km.persistence.mysql.group.GroupMemberDAO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class GroupServiceImplTest {
    private static final Long CLUSTER_ID = 1L;
    private static final String GROUP_NAME = "orders-consumer";
    private static final String TOPIC_NAME = "orders";

    private GroupServiceImpl groupService;
    private GroupDAO groupDAO;
    private GroupMemberDAO groupMemberDAO;

    @BeforeEach
    public void setUp() {
        groupService = new GroupServiceImpl();
        groupDAO = mock(GroupDAO.class);
        groupMemberDAO = mock(GroupMemberDAO.class);
        ReflectionTestUtils.setField(groupService, "groupDAO", groupDAO);
        ReflectionTestUtils.setField(groupService, "groupMemberDAO", groupMemberDAO);
    }

    @Test
    public void deletesStaleMembershipWithoutDeletingAnExistingGroupWithTheSameId() {
        Group currentGroup = group(Collections.emptyList());
        GroupPO existingGroup = groupPO(42L, currentGroup);
        GroupMemberPO staleMember = memberPO(42L, 1);
        when(groupDAO.selectList(any())).thenReturn(Collections.singletonList(existingGroup));
        when(groupMemberDAO.selectList(any())).thenReturn(Collections.singletonList(staleMember));

        groupService.batchReplaceGroupsAndMembers(CLUSTER_ID,
                Collections.singletonList(currentGroup), Collections.emptySet());

        verify(groupMemberDAO).deleteById(staleMember.getId());
        verify(groupDAO, never()).deleteById(any());
    }

    @Test
    public void retainsGroupsAndMembershipsWhenTheGroupRefreshFailed() {
        GroupPO existingGroup = groupPO(10L, group(Collections.emptyList()));
        GroupMemberPO existingMember = memberPO(20L, 1);
        when(groupDAO.selectList(any())).thenReturn(Collections.singletonList(existingGroup));
        when(groupMemberDAO.selectList(any())).thenReturn(Collections.singletonList(existingMember));

        groupService.batchReplaceGroupsAndMembers(CLUSTER_ID,
                Collections.emptyList(), Collections.singleton(GROUP_NAME));

        verify(groupDAO, never()).deleteById(any());
        verify(groupMemberDAO, never()).deleteById(any());
    }

    @Test
    public void retainsUnchangedMemberships() {
        Group currentGroup = group(Collections.singletonList(new GroupTopicMember(TOPIC_NAME, 2)));
        GroupPO existingGroup = groupPO(10L, currentGroup);
        GroupMemberPO existingMember = memberPO(20L, 2);
        when(groupDAO.selectList(any())).thenReturn(Collections.singletonList(existingGroup));
        when(groupMemberDAO.selectList(any())).thenReturn(Collections.singletonList(existingMember));

        groupService.batchReplaceGroupsAndMembers(CLUSTER_ID,
                Collections.singletonList(currentGroup), Collections.emptySet());

        verify(groupMemberDAO, never()).insert(any());
        verify(groupMemberDAO, never()).updateById(any());
        verify(groupMemberDAO, never()).deleteById(any());
        verify(groupDAO, never()).deleteById(any());
    }

    @Test
    public void updatesExistingMembershipsWithoutDeletingTheirGroups() {
        Group previousGroup = group(Collections.singletonList(new GroupTopicMember(TOPIC_NAME, 1)));
        Group currentGroup = group(Collections.singletonList(new GroupTopicMember(TOPIC_NAME, 3)));
        GroupPO existingGroup = groupPO(10L, previousGroup);
        GroupMemberPO existingMember = memberPO(20L, 1);
        when(groupDAO.selectList(any())).thenReturn(Collections.singletonList(existingGroup));
        when(groupMemberDAO.selectList(any())).thenReturn(Collections.singletonList(existingMember));

        groupService.batchReplaceGroupsAndMembers(CLUSTER_ID,
                Collections.singletonList(currentGroup), Collections.emptySet());

        ArgumentCaptor<GroupMemberPO> updatedMember = ArgumentCaptor.forClass(GroupMemberPO.class);
        verify(groupMemberDAO).updateById(updatedMember.capture());
        assertEquals(existingMember.getId(), updatedMember.getValue().getId());
        assertEquals(CLUSTER_ID, updatedMember.getValue().getClusterPhyId());
        assertEquals(GROUP_NAME, updatedMember.getValue().getGroupName());
        assertEquals(TOPIC_NAME, updatedMember.getValue().getTopicName());
        assertEquals(Integer.valueOf(3), updatedMember.getValue().getMemberCount());
        assertEquals(GroupStateEnum.ACTIVE.getState(), updatedMember.getValue().getState());
        verify(groupMemberDAO, never()).insert(any());
        verify(groupMemberDAO, never()).deleteById(any());
        verify(groupDAO, never()).deleteById(any());
    }

    @Test
    public void deletesExpiredGroupsAndTheirMembershipsFromTheirOwnTables() {
        GroupPO expiredGroup = groupPO(10L, group(Collections.emptyList()));
        GroupMemberPO expiredMember = memberPO(20L, 1);
        when(groupDAO.selectList(any())).thenReturn(Collections.singletonList(expiredGroup));
        when(groupMemberDAO.selectList(any())).thenReturn(Collections.singletonList(expiredMember));

        groupService.batchReplaceGroupsAndMembers(CLUSTER_ID,
                Collections.emptyList(), Collections.emptySet());

        verify(groupDAO).deleteById(expiredGroup.getId());
        verify(groupMemberDAO).deleteById(expiredMember.getId());
        verify(groupDAO, never()).deleteById(expiredMember.getId());
    }

    private Group group(List<GroupTopicMember> topicMembers) {
        Group group = new Group();
        group.setClusterPhyId(CLUSTER_ID);
        group.setType(GroupTypeEnum.CONSUMER);
        group.setName(GROUP_NAME);
        group.setState(topicMembers.isEmpty() ? GroupStateEnum.EMPTY : GroupStateEnum.ACTIVE);
        group.setMemberCount(topicMembers.stream().mapToInt(GroupTopicMember::getMemberCount).sum());
        group.setTopicMembers(topicMembers);
        group.setPartitionAssignor("range");
        group.setCoordinatorId(1);
        return group;
    }

    private GroupPO groupPO(Long id, Group group) {
        GroupPO groupPO = GroupConverter.convert2GroupPO(group);
        groupPO.setId(id);
        return groupPO;
    }

    private GroupMemberPO memberPO(Long id, Integer memberCount) {
        GroupMemberPO member = new GroupMemberPO(CLUSTER_ID, TOPIC_NAME, GROUP_NAME,
                GroupStateEnum.ACTIVE.getState(), memberCount);
        member.setId(id);
        return member;
    }
}
