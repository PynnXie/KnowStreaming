package com.xiaojukeji.know.streaming.km.core.service.group;

import com.xiaojukeji.know.streaming.km.common.bean.entity.group.Group;
import com.xiaojukeji.know.streaming.km.common.bean.entity.group.GroupTopicMember;
import com.xiaojukeji.know.streaming.km.common.bean.po.group.GroupMemberPO;
import com.xiaojukeji.know.streaming.km.common.enums.group.GroupStateEnum;
import com.xiaojukeji.know.streaming.km.common.enums.group.GroupTypeEnum;
import com.xiaojukeji.know.streaming.km.core.service.group.impl.GroupServiceImpl;
import com.xiaojukeji.know.streaming.km.persistence.mysql.group.GroupDAO;
import com.xiaojukeji.know.streaming.km.persistence.mysql.group.GroupMemberDAO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class GroupMembershipKeyCollisionTest {
    private static final Long CLUSTER_ID = 1L;

    private GroupServiceImpl service;
    private GroupDAO groupDAO;
    private GroupMemberDAO groupMemberDAO;

    @BeforeEach
    public void setUp() {
        service = new GroupServiceImpl();
        groupDAO = mock(GroupDAO.class);
        groupMemberDAO = mock(GroupMemberDAO.class);
        ReflectionTestUtils.setField(service, "groupDAO", groupDAO);
        ReflectionTestUtils.setField(service, "groupMemberDAO", groupMemberDAO);
        when(groupDAO.selectList(any())).thenReturn(Collections.emptyList());
    }

    @Test
    public void differentGroupTopicPairsWithSameConcatenationStayDistinct() {
        GroupMemberPO first = member(10L, "a", "bc", 1);
        GroupMemberPO second = member(20L, "ab", "c", 2);
        when(groupMemberDAO.selectList(any())).thenReturn(Arrays.asList(first, second));

        service.batchReplaceGroupsAndMembers(CLUSTER_ID,
                Arrays.asList(group("a", "bc", 1), group("ab", "c", 2)),
                Collections.emptySet());

        verify(groupMemberDAO, never()).insert(any());
        verify(groupMemberDAO, never()).updateById(any());
        verifyNoDeletes();
    }

    @Test
    public void collidingPairDoesNotOverwriteAnotherGroupsMembership() {
        // The existing group could not be refreshed, so its membership must be preserved.
        GroupMemberPO previous = member(10L, "a", "bc", 1);
        when(groupMemberDAO.selectList(any())).thenReturn(Collections.singletonList(previous));

        service.batchReplaceGroupsAndMembers(CLUSTER_ID,
                Collections.singletonList(group("ab", "c", 2)),
                Collections.singleton("a"));

        ArgumentCaptor<GroupMemberPO> inserted = ArgumentCaptor.forClass(GroupMemberPO.class);
        verify(groupMemberDAO).insert(inserted.capture());
        assertNull(inserted.getValue().getId());
        assertEquals(CLUSTER_ID, inserted.getValue().getClusterPhyId());
        assertEquals("ab", inserted.getValue().getGroupName());
        assertEquals("c", inserted.getValue().getTopicName());
        assertEquals(Integer.valueOf(2), inserted.getValue().getMemberCount());
        verify(groupMemberDAO, never()).updateById(any());
        verifyNoDeletes();
    }

    @Test
    public void exactGroupTopicPairStillUpdatesItsOwnRow() {
        GroupMemberPO previous = member(33L, "a", "bc", 1);
        when(groupMemberDAO.selectList(any())).thenReturn(Collections.singletonList(previous));

        service.batchReplaceGroupsAndMembers(CLUSTER_ID,
                Collections.singletonList(group("a", "bc", 3)),
                Collections.emptySet());

        ArgumentCaptor<GroupMemberPO> updated = ArgumentCaptor.forClass(GroupMemberPO.class);
        verify(groupMemberDAO).updateById(updated.capture());
        assertEquals(previous.getId(), updated.getValue().getId());
        assertEquals("a", updated.getValue().getGroupName());
        assertEquals("bc", updated.getValue().getTopicName());
        assertEquals(Integer.valueOf(3), updated.getValue().getMemberCount());
        verify(groupMemberDAO, never()).insert(any());
        verifyNoDeletes();
    }

    @Test
    public void collidingPairsUpdateTheirOwnIdsAndMemberCounts() {
        when(groupMemberDAO.selectList(any())).thenReturn(Arrays.asList(
                member(10L, "a", "bc", 1), member(20L, "ab", "c", 2)));

        service.batchReplaceGroupsAndMembers(CLUSTER_ID,
                Arrays.asList(group("a", "bc", 3), group("ab", "c", 4)),
                Collections.emptySet());

        ArgumentCaptor<GroupMemberPO> updated = ArgumentCaptor.forClass(GroupMemberPO.class);
        verify(groupMemberDAO, times(2)).updateById(updated.capture());
        List<GroupMemberPO> values = updated.getAllValues();
        assertEquals(Long.valueOf(10L), values.get(0).getId());
        assertEquals("a", values.get(0).getGroupName());
        assertEquals("bc", values.get(0).getTopicName());
        assertEquals(Integer.valueOf(3), values.get(0).getMemberCount());
        assertEquals(Long.valueOf(20L), values.get(1).getId());
        assertEquals("ab", values.get(1).getGroupName());
        assertEquals("c", values.get(1).getTopicName());
        assertEquals(Integer.valueOf(4), values.get(1).getMemberCount());
        verify(groupMemberDAO, never()).insert(any());
        verifyNoDeletes();
    }

    @Test
    public void newPairsWithSameConcatenationAreBothInserted() {
        when(groupMemberDAO.selectList(any())).thenReturn(Collections.emptyList());

        service.batchReplaceGroupsAndMembers(CLUSTER_ID,
                Arrays.asList(group("a", "bc", 1), group("ab", "c", 2)),
                Collections.emptySet());

        ArgumentCaptor<GroupMemberPO> inserted = ArgumentCaptor.forClass(GroupMemberPO.class);
        verify(groupMemberDAO, times(2)).insert(inserted.capture());
        List<GroupMemberPO> values = inserted.getAllValues();
        assertNull(values.get(0).getId());
        assertEquals("a", values.get(0).getGroupName());
        assertEquals("bc", values.get(0).getTopicName());
        assertNull(values.get(1).getId());
        assertEquals("ab", values.get(1).getGroupName());
        assertEquals("c", values.get(1).getTopicName());
        verify(groupMemberDAO, never()).updateById(any());
        verifyNoDeletes();
    }

    private void verifyNoDeletes() {
        verify(groupDAO, never()).deleteById(any());
        verify(groupMemberDAO, never()).deleteById(any());
    }

    private Group group(String groupName, String topicName, int memberCount) {
        Group group = new Group();
        group.setClusterPhyId(CLUSTER_ID);
        group.setType(GroupTypeEnum.CONSUMER);
        group.setName(groupName);
        group.setState(GroupStateEnum.ACTIVE);
        group.setMemberCount(memberCount);
        group.setTopicMembers(Collections.singletonList(new GroupTopicMember(topicName, memberCount)));
        group.setPartitionAssignor("range");
        group.setCoordinatorId(1);
        return group;
    }

    private GroupMemberPO member(Long id, String groupName, String topicName, int memberCount) {
        GroupMemberPO member = new GroupMemberPO(CLUSTER_ID, topicName, groupName,
                GroupStateEnum.ACTIVE.getState(), memberCount);
        member.setId(id);
        return member;
    }
}
